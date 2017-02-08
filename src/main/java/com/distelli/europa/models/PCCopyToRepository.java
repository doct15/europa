package com.distelli.europa.models;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Singular;
import javax.inject.Inject;
import com.distelli.europa.db.RegistryManifestDb;
import com.distelli.europa.db.ContainerRepoDb;
import com.distelli.europa.db.RepoEventsDb;
import lombok.extern.log4j.Log4j;
import java.util.Collections;
import com.distelli.webserver.AjaxClientException;
import com.distelli.webserver.JsonError;
import javax.inject.Provider;
import com.distelli.gcr.GcrClient;
import com.distelli.gcr.GcrRegion;
import com.distelli.gcr.exceptions.GcrException;
import com.distelli.gcr.models.GcrManifest;
import com.distelli.gcr.models.GcrManifestV2Schema1;
import com.distelli.gcr.models.GcrManifestMeta;
import com.distelli.gcr.models.GcrBlobUpload;
import com.distelli.gcr.models.GcrBlobReader;
import com.distelli.gcr.models.GcrBlobMeta;
import com.distelli.gcr.auth.GcrCredentials;
import com.distelli.gcr.auth.GcrServiceAccountCredentials;
import com.distelli.europa.db.RegistryCredsDb;
import com.distelli.europa.db.RegistryBlobDb;
import java.net.URI;
import java.io.IOException;
import java.io.InputStream;
import com.distelli.objectStore.ObjectStore;
import com.distelli.objectStore.ObjectKey;
import com.distelli.europa.util.ObjectKeyFactory;
import static java.nio.charset.StandardCharsets.UTF_8;
import static com.distelli.europa.Constants.DOMAIN_ZERO;
import java.util.List;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import com.distelli.utils.CountingInputStream;
import com.distelli.utils.ResettableInputStream;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * Pipeline component that copies from one repository to another.
 */
@Log4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PCCopyToRepository extends PipelineComponent {
    private String destinationContainerRepoDomain;
    private String destinationContainerRepoId;
    private String tag;
    private Long lastExecutionTime;
    private ExecutionStatus lastExecutionStatus;

    @Inject
    private RegistryBlobDb _blobDb;
    @Inject
    private RegistryManifestDb _manifestDb;
    @Inject
    private ContainerRepoDb _repoDb;
    @Inject
    private RepoEventsDb _eventDb;
    @Inject
    private Provider<GcrClient.Builder> _gcrClientBuilderProvider;
    @Inject
    protected RegistryCredsDb _registryCredsDb = null;
    @Inject
    private Provider<ObjectKeyFactory> _objectKeyFactoryProvider;
    @Inject
    private Provider<ObjectStore> _objectStoreProvider;

    @Override
    public boolean execute(ContainerRepo srcRepo, String srcTag, String manifestDigestSha) throws Exception {
        if ( null == _repoDb || null == _manifestDb ) {
            throw new IllegalStateException("Injector.injectMembers(this) has not been called");
        }
        if ( null == srcRepo ) {
            throw new IllegalStateException("ContainerRepo must not be null");
        }
        if ( null == srcTag ) {
            throw new IllegalStateException("Tag must not be null");
        }
        // Not configured? Ignore...
        if ( null == destinationContainerRepoId || 
             null == destinationContainerRepoDomain )
        {
            log.error("PipelineComponentId="+getId()+" has null destinationContainerRepoId or destinationContainerRepoDomain");
            return true;
        }
        // From the same repo? Ignore...
        if ( destinationContainerRepoId.equals(srcRepo.getId()) ) {
            log.error("PipelineComponentId="+getId()+" pushes to itself!?");
            return true;
        }
        // TODO: if manifestDigestSha is null, we should issue a "DELETE"
        if ( null == manifestDigestSha ) {
            log.debug("Tag delete is not implemented");
            return true;
        }
        ContainerRepo destRepo = _repoDb.getRepo(destinationContainerRepoDomain, destinationContainerRepoId);
        // To repo that doesn't exist...
        if ( null == destRepo ) {
            // This will happen when a repo referenced by a
            // pipeline is deleted, so debug log level:
            log.debug("PipelineComponentId="+getId()+" repo does not exist domain="+
                      destinationContainerRepoDomain+" id="+
                      destinationContainerRepoId);
            return true;
        }
        if ( srcRepo.isLocal() && destRepo.isLocal() ) {
            // Optimization, simply update the DB:
            RegistryManifest manifest = _manifestDb.getManifestByRepoIdTag(
                srcRepo.getDomain(),
                srcRepo.getId(),
                manifestDigestSha);
            if ( null == manifest ) {
                log.error("PipelineComponentId="+getId()+" missing manifest for domain="+srcRepo.getDomain()+
                          " repoId="+srcRepo.getId()+" tag="+manifestDigestSha);
                return true;
            }
            RegistryManifest copy = manifest.toBuilder()
                .domain(destRepo.getDomain())
                .containerRepoId(destRepo.getId())
                .tag(null == tag ? srcTag : tag)
                .build();
            _manifestDb.put(copy);
            RepoEvent event = RepoEvent.builder()
                .domain(copy.getDomain())
                .repoId(copy.getContainerRepoId())
                .eventType(RepoEventType.PUSH)
                .eventTime(System.currentTimeMillis())
                .imageTags(Collections.singletonList(copy.getTag()))
                .imageSha(copy.getManifestId())
                .build();
            _eventDb.save(event);
            _repoDb.setLastEvent(event.getDomain(), event.getRepoId(), event);
        } else {
            Registry srcRegistry = createRegistry(srcRepo);
            Registry dstRegistry = createRegistry(destRepo);
            if ( null == dstRegistry || null == srcRegistry ) return true;
            GcrManifest manifest = srcRegistry.getManifest(srcRepo.getName(), manifestDigestSha);
            if ( null == manifest ) {
                log.error("Manifest not found for repo="+srcRepo.getName()+" ref="+manifestDigestSha);
                return true;
            }
            String reference = tag;
            if ( null == reference ) reference = srcTag;
            genericCopy(manifest, srcRegistry, srcRepo.getName(), srcTag, dstRegistry, destRepo.getName(),reference);
        }
        return true;
    }

    interface Registry {
        public GcrManifest getManifest(String repository, String reference)
            throws IOException;
        public <T> T getBlob(String repository, String digest, GcrBlobReader<T> reader)
            throws IOException;
        public GcrBlobUpload createBlobUpload(String repository, String digest, String fromRepository)
            throws IOException;
        public GcrBlobMeta blobUploadChunk(GcrBlobUpload blobUpload, InputStream chunk, Long chunkLength, String digest)
            throws IOException;
        public GcrManifestMeta putManifest(String repository, String reference, GcrManifest manifest)
            throws IOException;
    }

    class EuropaRegistry implements Registry {
        private ContainerRepo repo;
        public EuropaRegistry(ContainerRepo repo) {
            this.repo = repo;
        }
        @Override
        public GcrManifest getManifest(String repository, String reference) throws IOException {
            if ( ! repo.getName().equals(repository) ) {
                throw new IllegalArgumentException(
                    "Expected repo.name="+repo.getName()+", but got repository="+repository);
            }
            RegistryManifest manifest = _manifestDb.getManifestByRepoIdTag(repo.getDomain(), repo.getId(), reference);
            if ( null == manifest ) return null;
            ObjectKey key = _objectKeyFactoryProvider.get()
                .forRegistryManifest(manifest.getManifestId());
            byte[] binary = _objectStoreProvider.get().get(key);
            if ( null == binary ) return null;
            String manifestContent = new String(binary, UTF_8);
            return new GcrManifest() {
                public String getMediaType() {
                    return manifest.getContentType();
                }
                public String toString() {
                    return manifestContent;
                }
                public List<String> getReferencedDigests() {
                    return new ArrayList<>(manifest.getDigests());
                }
            };
        }

        @Override
        public <T> T getBlob(String repository, String digest, GcrBlobReader<T> reader) throws IOException {
            RegistryBlob blob = _blobDb.getRegistryBlobByDigest(digest.toLowerCase());
            if ( null == blob ) {
                return reader.read(new ByteArrayInputStream(new byte[0]), null);
            }
            ObjectKey key = _objectKeyFactoryProvider.get()
                .forRegistryBlobId(blob.getBlobId());
            return _objectStoreProvider.get().get(key, (meta, in) -> {
                    return reader.read(
                        in,
                        GcrBlobMeta.builder()
                        .digest(digest)
                        .length(meta.getContentLength())
                        .build());
                });
        }

        @Override
        public GcrBlobUpload createBlobUpload(String repository, String digest, String fromRepository) throws IOException {
            RegistryBlob blob = _blobDb.getRegistryBlobByDigest(digest.toLowerCase());
            return GcrBlobUpload.builder()
                .complete(null != blob)
                .digest(digest)
                .mediaType(null != blob ? blob.getMediaType() : null)
                .build();
        }

        @Override
        public GcrBlobMeta blobUploadChunk(GcrBlobUpload blobUpload, InputStream chunk, Long chunkLength, String digest) throws IOException {
            // TODO: Get the pipeline domain!
            RegistryBlob blob = _blobDb.newRegistryBlob(DOMAIN_ZERO);
            ObjectKey key = _objectKeyFactoryProvider.get()
                .forRegistryBlobId(blob.getBlobId());
            if ( null == chunkLength ) {
                // Buffer on disk to determine object size:
                chunk = new ResettableInputStream(chunk);
                CountingInputStream counter = new CountingInputStream(chunk);
                byte[] buff = new byte[1024*1024];
                while ( counter.read(buff) > 0 );
                chunkLength = counter.getCount();
                chunk.reset();
            }
            MessageDigest md = getSha256();
            chunk = new DigestInputStream(chunk, md);
            _objectStoreProvider.get().put(key, chunkLength, chunk);
            String expectDigest = "sha256:" + printHexBinary(md.digest()).toLowerCase();
            if ( ! digest.equals(expectDigest) ) {
                throw new IllegalArgumentException("Computed digest="+expectDigest+", but declared digest="+digest);
            }
            _blobDb.finishUpload(blob.getBlobId(),
                                 null,
                                 digest,
                                 chunkLength,
                                 blobUpload.getMediaType());
            return GcrBlobMeta.builder()
                .length(chunkLength)
                .digest(digest)
                .build();
        }

        private MessageDigest getSha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch ( Exception ex ) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public GcrManifestMeta putManifest(String repository, String reference, GcrManifest gcrManifest) throws IOException {
            if ( ! repo.getName().equals(repository) ) {
                throw new IllegalArgumentException(
                    "Expected repo.name="+repo.getName()+", but got repository="+repository);
            }
            byte[] binary = gcrManifest.toString().getBytes(UTF_8);
            MessageDigest md = getSha256();
            String digest = "sha256:"+printHexBinary(md.digest(binary)).toLowerCase();
            RegistryManifest manifest = RegistryManifest.builder()
                // TODO: Get the pipeline domain!
                .uploadedBy(DOMAIN_ZERO)
                .contentType(gcrManifest.getMediaType())
                .manifestId(digest)
                .domain(repo.getDomain())
                .containerRepoId(repo.getId())
                .tag(reference)
                .digests(gcrManifest.getReferencedDigests())
                .pushTime(System.currentTimeMillis())
                .build();
            _manifestDb.put(manifest);
            RepoEvent event = RepoEvent.builder()
                .domain(manifest.getDomain())
                .repoId(manifest.getContainerRepoId())
                .eventType(RepoEventType.PUSH)
                .eventTime(System.currentTimeMillis())
                .imageTags(Collections.singletonList(manifest.getTag()))
                .imageSha(manifest.getManifestId())
                .build();
            _eventDb.save(event);
            _repoDb.setLastEvent(event.getDomain(), event.getRepoId(), event);
            // TODO: Should we trigger other pipelines? .. perhaps we should move pipeline triggers
            // for europa repositories into the monitor stuff. This way we can avoid pipeline
            // execution overlap.
            return GcrManifestMeta.builder()
                .digest(digest)
                .location(null) // unknown...
                .mediaType(manifest.getContentType())
                .build();
        }
    }

    class GcrRegistry implements Registry {
        private GcrClient client;
        public GcrRegistry(RegistryCred cred) {
            client = _gcrClientBuilderProvider.get()
                .gcrCredentials(new GcrServiceAccountCredentials(cred.getSecret()))
                .gcrRegion(GcrRegion.getRegion(cred.getRegion()))
                .build();
            this.client = client;
        }
        @Override
        public GcrManifest getManifest(String repository, String reference) throws IOException {
            return client.getManifest(repository, reference);
        }
            
        @Override
        public <T> T getBlob(String repository, String digest, GcrBlobReader<T> reader) throws IOException {
            return client.getBlob(repository, digest, reader);
        }
            
        @Override
        public GcrBlobUpload createBlobUpload(String repository, String digest, String fromRepository) throws IOException {
            return client.createBlobUpload(repository, digest, fromRepository);
        }
            
        @Override
        public GcrBlobMeta blobUploadChunk(GcrBlobUpload blobUpload, InputStream chunk, Long chunkLength, String digest) throws IOException {
            return client.blobUploadChunk(blobUpload, chunk, chunkLength, digest);
        }
            
        @Override
        public GcrManifestMeta putManifest(String repository, String reference, GcrManifest manifest) throws IOException {
            // GCR only accepts V2 schema 1 manifests:
            if ( ! ( GcrManifestV2Schema1.MEDIA_TYPE.equals(manifest.getMediaType())
                     || GcrManifestV2Schema1.SIGNED_MEDIA_TYPE.equals(manifest.getMediaType()) ) )
            {
                log.error("TODO: Support convertion from "+manifest.getMediaType()+" to "+GcrManifestV2Schema1.MEDIA_TYPE);
                return null;
            }
            return client.putManifest(repository, reference, manifest);
        }
    }

    private Registry createRegistry(ContainerRepo repo) {
        RegistryCred cred = null;
        if ( null != repo.getCredId() ) {
            cred = _registryCredsDb.getCred(repo.getDomain(), repo.getCredId());
        }
        switch ( repo.getProvider() ) {
        case DOCKERHUB:
            // TODO: https://docs.docker.com/registry/spec/auth/token/
            throw new UnsupportedOperationException();
        case GCR:
            if ( null == cred ) {
                log.error("Missing creds with id="+repo.getCredId()+" for repo="+repo);
                return null;
            }
            return new GcrRegistry(cred);
        case ECR:
            // TODO:
            throw new UnsupportedOperationException();
        case EUROPA:
            return new EuropaRegistry(repo);
        default:
            throw new UnsupportedOperationException(
                "CopyToRepository does not support provider="+repo.getProvider());
        }
    }

    private void genericCopy(
        GcrManifest srcManifest,
        Registry srcRegistry,
        String srcRepo,
        String srcTag,
        Registry dstRegistry,
        String dstRepo,
        String dstTag)
        throws IOException
    {
        // Uplaod the referenced digests:
        for ( String digest : srcManifest.getReferencedDigests() ) {
            GcrBlobUpload upload = dstRegistry.createBlobUpload(dstRepo, digest, srcRepo);
            if ( upload.isComplete() ) continue;
            // TODO: Get the media type of the reference digests:
            // upload.setMediaType();
            srcRegistry.getBlob(srcRepo, digest, (in, meta) ->
                                dstRegistry.blobUploadChunk(upload, in, meta.getLength(), digest));
        }
        // Upload the manifest:
        dstRegistry.putManifest(dstRepo, dstTag, srcManifest);
    }

    @Override
    public void validate(String key) {
        if ( null == destinationContainerRepoDomain ) {
            throw new AjaxClientException(
                "Missing Param '"+key+".destinationContainerRepoDomain' in request",
                JsonError.Codes.MissingParam,
                400);
        }
        if ( null == destinationContainerRepoId) {
            throw new AjaxClientException(
                "Missing Param '"+key+".destinationContainerRepoId' in request",
                JsonError.Codes.MissingParam,
                400);
        }
    }

    protected PCCopyToRepository(String id, String destinationContainerRepoDomain, String destinationContainerRepoId, String tag, Long lastExecutionTime, ExecutionStatus lastExecutionStatus) {
        super(id);
        this.destinationContainerRepoDomain = destinationContainerRepoDomain;
        this.destinationContainerRepoId = destinationContainerRepoId;
        this.tag = tag;
        this.lastExecutionTime = lastExecutionTime;
        this.lastExecutionStatus = lastExecutionStatus;
    }

    public static class Builder<T extends Builder<T>> extends PipelineComponent.Builder<T> {
        protected String destinationContainerRepoDomain;
        protected String destinationContainerRepoId;
        protected String tag;
        protected Long lastExecutionTime;
        protected ExecutionStatus lastExecutionStatus;

        public T destinationContainerRepoDomain(String destinationContainerRepoDomain) {
            this.destinationContainerRepoDomain = destinationContainerRepoDomain;
            return self();
        }

        public T destinationContainerRepoId(String destinationContainerRepoId) {
            this.destinationContainerRepoId = destinationContainerRepoId;
            return self();
        }

        public T tag(String tag) {
            this.tag = tag;
            return self();
        }

        public T lastExecutionTime(Long lastExecutionTime) {
            this.lastExecutionTime = lastExecutionTime;
            return self();
        }

        public T lastExecutionStatus(ExecutionStatus lastExecutionStatus) {
            this.lastExecutionStatus = lastExecutionStatus;
            return self();
        }

        public PCCopyToRepository build() {
            return new PCCopyToRepository(id, destinationContainerRepoDomain, destinationContainerRepoId, tag, lastExecutionTime, lastExecutionStatus);
        }
    }

    public static Builder<?> builder() {
        return new Builder();
    }
}
