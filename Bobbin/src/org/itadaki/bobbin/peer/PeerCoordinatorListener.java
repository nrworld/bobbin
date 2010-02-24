package org.itadaki.bobbin.peer;


/**
 * A listener interface for PeerCoordinator events
 */
public interface PeerCoordinatorListener {

	/**
	 * Indicates that all the wanted pieces of the torrent have been acquired
	 */
	public void peerCoordinatorCompleted();

	/**
	 * Indicates that a new, fully connected peer has been registered with the peer coordinator
	 *
	 * @param peer The peer that has been registered
	 */
	public void peerRegistered (ManageablePeer peer);

	/**
	 * Indicates that a registered peer has disconnected
	 *
	 * @param peer The peer that has disconnected
	 */
	public void peerDeregistered (ManageablePeer peer);

}
