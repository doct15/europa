/*
  @author Sam Heutmaker [samheutmaker@gmail.com]
*/

import React, { Component, PropTypes } from 'react'

export default class ControlRoom extends Component {
	constructor(props) {
		super(props);
		this.state = {};
	}
	renderHeader(){
		if(this.props.renderHeaderContent) {
			return (
				<div className="Header" style={this.props.headerStyle || {}}>
					{this.props.renderHeaderContent()}
				</div>
			);
		}
	}
	renderBody(){
		return (
			<div className="Body" style={this.props.bodyStyle || {}}>
				{this.props.renderBodyContent()}
			</div>
		);
	}
	render() {
		return (
			<div className="ControlRoom">
				{this.renderHeader()}
				{this.renderBody()}
			</div>
		);
		
	}
}

ControlRoom.propTypes = {
	renderBodyContent: PropTypes.func.isRequired,
	bodyStyle: PropTypes.object,
	bodyClassName: PropTypes.string,
	renderHeaderContent: PropTypes.func,
	headerStyle: PropTypes.object,
	headerClassName: PropTypes.string,
};

ControlRoom.childContextTypes = {
    actions: PropTypes.object,
    state: PropTypes.object
};

ControlRoom.contextTypes = {
    actions: PropTypes.object,
    state: PropTypes.object
};