/*
  @author Sam Heutmaker [samheutmaker@gmail.com]
*/

import React, {Component} from 'react'
import Btn from './../components/Btn'

export default class NotFound extends Component {
	constructor(props) {
		super(props);
		this.state = {};
	}
	toHome(){
		this.context.router.push('/');
	}
	render() {
		return (
			<div className="ContentContainer">
				<div className="PageHeader">
					<h2>
						Page Not Found
					</h2>
				</div>
				<div>
					<Btn onClick={() => this.toHome()} 
						 className="LargeBlueButton"
						 text="Take Me Home" 
						 style={{marginTop: '21px'}}
						 canClick={true}/>
				</div>
			</div>
		);
	}
}

NotFound.childContextTypes = {
    actions: React.PropTypes.object,
    state: React.PropTypes.object,
    router: React.PropTypes.object
};

NotFound.contextTypes = {
    actions: React.PropTypes.object,
    state: React.PropTypes.object,
    router: React.PropTypes.object
};
