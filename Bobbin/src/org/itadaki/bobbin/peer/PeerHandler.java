/*
 * Copyright (c) 2010 Matthew J. Francis and Contributors of the Bobbin Project
 * This file is distributed under the MIT licence. See the LICENCE file for further information.
 */
package org.itadaki.bobbin.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import org.itadaki.bobbin.bencode.BDictionary;
import org.itadaki.bobbin.connectionmanager.Connection;
import org.itadaki.bobbin.connectionmanager.ConnectionManager;
import org.itadaki.bobbin.connectionmanager.ConnectionReadyListener;
import org.itadaki.bobbin.peer.protocol.PeerProtocolConstants;
import org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer;
import org.itadaki.bobbin.peer.protocol.PeerProtocolParser;
import org.itadaki.bobbin.torrentdb.BlockDescriptor;
import org.itadaki.bobbin.torrentdb.InfoHash;
import org.itadaki.bobbin.torrentdb.PieceDatabase;
import org.itadaki.bobbin.torrentdb.StorageDescriptor;
import org.itadaki.bobbin.torrentdb.ViewSignature;
import org.itadaki.bobbin.util.BitField;
import org.itadaki.bobbin.util.counter.StatisticCounter;
import org.itadaki.bobbin.util.elastictree.HashChain;



/**
 * A {@code PeerHandler} controls the interaction between the local peer and a single remote peer,
 * standing between a {@link ConnectionManager} which controls the underlying data connection to the
 * remote peer, and a {@link PeerCoordinator} (through the {@link PeerServices} interface) which
 * coordinates request management and peer choking globally across a torrent.
 *
 * <p>When the {@link ConnectionManager} is ready to send or receive bytes across the connection to
 * a remote peer, it causes the {@link Connection} that is associated with the peer's
 * {@link PeerHandler} to signal that readiness on its {@link ConnectionReadyListener} interface.
 * The {@link PeerHandler} then reads and writes as many bytes as it can through the
 * {@link Connection}.
 *
 * <p>Bytes that are read are processed through a {@link PeerProtocolParser}, which in turn calls
 * one of the {@link PeerProtocolConsumer} methods on the {@code PeerHandler} as soon as a either a
 * complete message is received, or a protocol error in the input data is detected.
 *
 * <p>In response to completely received messages, the {@link PeerHandler} will update its internal
 * representation of the remote peer's state, delegate any actions with torrent global impact to its
 * {@link PeerCoordinator}, and inform its {@link PeerOutboundQueue} of any messages that should be
 * queued for sending to the remote peer.
 *
 * <p>Finally, the {@link PeerOutboundQueue} writes as many bytes as possible to the remote peer
 * through the {@link Connection}, keeping internal track of partly sent and unsent messages. Note
 * that the queue may order some messages in front of others, and that not all messages that are
 * requested may actually be sent; some message types (interested / not interested, request /
 * cancel) may instead, if their respective opposite message type is waiting unsent, cancel that
 * unsent message from the queue.
 *
 * <p>Actions that are delegated to a {@link PeerCoordinator} include the allocation of piece
 * requests to the remote peer, the handling of received piece block data, and the invocation of the
 * choking algorithm when required.
 */
public class PeerHandler implements PeerProtocolConsumer, ManageablePeer, ConnectionReadyListener {

	/**
	 * The connection to the remote peer
	 */
	private final Connection connection;

	/**
	 * The parser used to process incoming data
	 */
	private final PeerProtocolParser protocolParser;

	/**
	 * A counter for protocol bytes sent from this peer to the remote peer
	 */
	private final StatisticCounter protocolBytesSentCounter = new StatisticCounter();

	/**
	 * A counter for protocol bytes received by this peer from the remote peer
	 */
	private final StatisticCounter protocolBytesReceivedCounter = new StatisticCounter();

	/**
	 * A counter for piece block bytes sent from this peer to the remote peer
	 */
	private final StatisticCounter blockBytesSentCounter = new StatisticCounter();

	/**
	 * A counter for piece block bytes received by this peer from the remote peer
	 */
	private final StatisticCounter blockBytesReceivedCounter = new StatisticCounter();

	/**
	 * The PeerServicesProvider that will be asked to provide a PeerServices in the case of an
	 * incoming connection, once the info hash the remote peer wants is known
	 */
	private PeerServicesProvider peerServicesProvider;

	/**
	 * The PeerServices for the torrent that we are talking to the remote peer about
	 */
	private PeerServices peerServices;

	/**
	 * The torrent's FileDatabse
	 */
	private PieceDatabase pieceDatabase;

	/**
	 * The queue used to process outgoing data
	 */
	private PeerOutboundQueue outboundQueue;

	/**
	 * If {@code true}, the fast extension is enabled
	 */
	private boolean fastExtensionEnabled = true;

	/**
	 * If {@code true}, the extension protocol is enabled
	 */
	private boolean extensionProtocolEnabled = true;

	/**
	 * The set of extensions offered by the remote peer
	 */
	private Set<String> remoteExtensions = new HashSet<String>();

	/**
	 * If {@code true}, this peer is registered through a PeerServices
	 */
	private boolean registeredWithPeerServices = false;

	/**
	 * The remote peer's ID
	 */
	private PeerID remotePeerID = null;

	/**
	 * The remote peer's bitfield
	 */
	private BitField remoteBitField = null;

	/**
	 * The remote peer's view
	 */
	private StorageDescriptor remoteViewDescriptor;

	/**
	 * The set of signatures implicitly valid from the remote peer
	 */
	private NavigableMap<Long,ViewSignature> remotePeerSignatures = new TreeMap<Long,ViewSignature>();

	/**
	 * The shared info hash. In an outgoing connection this is set in the constructor; in an
	 * incoming connection, it is set during the handshake
	 */
	private InfoHash infoHash = null;

	/**
	 * True if we are choking the remote peer
	 */
	private boolean weAreChoking = true;

	/**
	 * True if we are interested in the remote peer
	 */
	private boolean weAreInterested = false;

	/**
	 * True if the remote peer is choking us
	 */
	private boolean theyAreChoking = true;

	/**
	 * True if the remote peer is interested in us
	 */
	private boolean theyAreInterested = false;

	/**
	 * The time in system milliseconds that the last data was received
	 */
	private long lastDataReceivedTime = 0;


	/* Peer interface */

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getPeerID()
	 */
	public PeerID getRemotePeerID() {

		return this.remotePeerID;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getRemoteAddress()
	 */
	public InetSocketAddress getRemoteSocketAddress() {

		return this.connection.getRemoteSocketAddress();

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getRemoteViewLength()
	 */
	public long getRemoteViewLength() {

		return this.remoteViewDescriptor.getLength();

	}

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#isFastExtensionEnabled()
	 */
	public boolean isFastExtensionEnabled() {

		return this.fastExtensionEnabled;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#isExtensionProtocolEnabled()
	 */
	public boolean isExtensionProtocolEnabled() {

		return this.extensionProtocolEnabled;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getWeChoke()
	 */
	public boolean getWeAreChoking() {

		return this.weAreChoking;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getWeAreInterested()
	 */
	public boolean getWeAreInterested() {

		return this.weAreInterested;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getTheyChoke()
	 */
	public boolean getTheyAreChoking() {

		return this.theyAreChoking;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getTheyAreInterested()
	 */
	public boolean getTheyAreInterested() {

		return this.theyAreInterested;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getBlocksReceived()
	 */
	public long getBlockBytesReceived() {

		return this.blockBytesReceivedCounter.getTotal();

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getBlocksSent()
	 */
	public long getBlockBytesSent() {

		return this.blockBytesSentCounter.getTotal();

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getProtocolBytesReceived()
	 */
	public long getProtocolBytesReceived() {

		return this.protocolBytesReceivedCounter.getTotal();

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getProtocolBytesSent()
	 */
	public long getProtocolBytesSent() {

		return this.protocolBytesSentCounter.getTotal();

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getProtocolBytesReceived()
	 */
	public int getProtocolBytesReceivedPerSecond() {

		return (int) this.protocolBytesReceivedCounter.getPeriodTotal (PeerCoordinator.TWO_SECOND_PERIOD) / 2;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getProtocolBytesSent()
	 */
	public int getProtocolBytesSentPerSecond() {

		return (int) this.protocolBytesSentCounter.getPeriodTotal (PeerCoordinator.TWO_SECOND_PERIOD) / 2;

	}


	/* ManageablePeer interface */


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#getBlocksReceivedCounter()
	 */
	public StatisticCounter getBlockBytesReceivedCounter() {

		return this.blockBytesReceivedCounter;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#getBlocksSentCounter()
	 */
	public StatisticCounter getBlockBytesSentCounter() {

		return this.blockBytesSentCounter;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#getTheyHaveOutstandingRequests()
	 */
	public boolean getTheyHaveOutstandingRequests() {

		return (this.outboundQueue.getUnsentPieceCount () > 0);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#setWeAreChoking(boolean)
	 */
	public boolean setWeAreChoking (boolean weAreChokingThem) {

		if (weAreChokingThem != this.weAreChoking) {
			this.weAreChoking = weAreChokingThem;
			// Any unsent block requests from the remote peer, except Allowed Fast requests, will be
			// discarded
			List<BlockDescriptor> descriptors = this.outboundQueue.sendChokeMessage (this.weAreChoking);
			if (this.fastExtensionEnabled) {
				// Explicitly discard requests
				this.outboundQueue.sendRejectRequestMessages (descriptors);
			}
			return true;
		}

		return false;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#setWeAreInterested(boolean)
	 */
	public void setWeAreInterested (boolean weAreInterested) {

		// Set our interest and inform the remote peer if needed
		if (weAreInterested != this.weAreInterested) {
			this.weAreInterested = weAreInterested;
			this.outboundQueue.sendInterestedMessage (weAreInterested);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#cancelRequests(java.util.List)
	 */
	public void cancelRequests (List<BlockDescriptor> requestsToCancel) {

		// Cancel the passed requests. If the Fast extension is enabled, indicate to the outbound
		// queue that it should continue to track the cancelled request so that it later matches
		// the remote peer's response (piece or reject)
		for (BlockDescriptor request : requestsToCancel) {
			this.outboundQueue.sendCancelMessage (request, this.fastExtensionEnabled);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#rejectPiece(int)
	 */
	public void rejectPiece (int pieceNumber) {

		this.outboundQueue.rejectPieceMessages(pieceNumber);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#sendHavePiece(int)
	 */
	public void sendHavePiece (int pieceNumber) {

		this.outboundQueue.sendHaveMessage (pieceNumber);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#sendKeepalive()
	 */
	public void sendKeepaliveOrClose() {

		if ((System.currentTimeMillis() - this.lastDataReceivedTime) > (PeerProtocolConstants.IDLE_INTERVAL * 1000)) {
			close();
		} else {
			this.outboundQueue.sendKeepaliveMessage();
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#sendViewSignature(org.itadaki.bobbin.peer.ViewSignature)
	 */
	public void sendViewSignature (ViewSignature viewSignature) {

		this.outboundQueue.sendElasticSignatureMessage (viewSignature);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ExtensiblePeer#sendExtensionHandshake(java.util.Set, java.util.Set, org.itadaki.bobbin.bencode.BDictionary)
	 */
	public void sendExtensionHandshake (Set<String> extensionsAdded, Set<String> extensionsRemoved, BDictionary extra) {

		this.outboundQueue.sendExtensionHandshake (extensionsAdded, extensionsRemoved, extra);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ExtensiblePeer#sendExtensionMessage(java.lang.String, java.nio.ByteBuffer)
	 */
	public void sendExtensionMessage (String identifier, ByteBuffer data) {

		this.outboundQueue.sendExtensionMessage (identifier, data);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#getBitField()
	 */
	public BitField getRemoteBitField() {

		return this.remoteBitField;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#close()
	 */
	public void close() {

		try {
			this.connection.close();
		} catch (IOException e) {
			// Shouldn't happen
		}
		if (this.peerServices != null) {
			this.peerServices.peerDisconnected (this);
		}

	}


	/* PeerProtocolConsumer interface */

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#handshakeExtensions(boolean)
	 */
	public void handshakeBasicExtensions (boolean fastExtensionEnabled, boolean extensionProtocolEnabled) {

		this.fastExtensionEnabled &= fastExtensionEnabled;
		this.extensionProtocolEnabled &= extensionProtocolEnabled;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#handshakeInfoHash(byte[])
	 */
	public void handshakeInfoHash (InfoHash infoHash) throws IOException {

		if (this.infoHash != null) {
			if (!this.infoHash.equals (infoHash)) {
				throw new IOException ("Invalid handshake - wrong info hash");
			}
		}

		// On an inbound connection, we don't initially know which PeerServices we should connect
		// to. If we can find it now, complete the PeerHandler's setup
		if (this.peerServices == null) {

			this.infoHash = infoHash;

			this.peerServices = this.peerServicesProvider.getPeerServices (this.infoHash);

			if (this.peerServices == null) {
				throw new IOException ("Invalid handshake - unknown info hash");
			}

			// We didn't have a PeerCoordinator on the way in, so it hasn't been locked yet
			this.peerServices.lock();

			// Complete setup of the PeerHandler
			completeSetupAndHandshake();

		}

		if (this.pieceDatabase.getInfo().isElastic()) {
			if (!(this.fastExtensionEnabled && this.extensionProtocolEnabled)) {
				throw new IOException ("Invalid handshake - no extension protocol or Fast extension on Elastic torrent");
			}
			this.outboundQueue.sendExtensionHandshake (new HashSet<String> (Arrays.asList (PeerProtocolConstants.EXTENSION_ELASTIC)), null, null);
			if (this.pieceDatabase.getStorageDescriptor().getLength() > this.pieceDatabase.getInfo().getStorageDescriptor().getLength()) {
				this.outboundQueue.sendElasticSignatureMessage (this.pieceDatabase.getViewSignature (this.pieceDatabase.getStorageDescriptor().getLength()));
			}
			this.outboundQueue.sendElasticBitfieldMessage (this.pieceDatabase.getPresentPieces());
		} else if (this.pieceDatabase.getInfo().isMerkle()) {
			this.outboundQueue.sendExtensionHandshake (new HashSet<String> (Arrays.asList (PeerProtocolConstants.EXTENSION_MERKLE)), null, null);
		};

		if (this.extensionProtocolEnabled) {
			this.peerServices.offerExtensionsToPeer (this);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#handshakePeerID(PeerID)
	 */
	public void handshakePeerID (PeerID peerID) throws IOException {

		this.remotePeerID = peerID;

		if (!this.peerServices.peerConnected (this)) {
			throw new IOException ("Peer registration rejected");
		}

		this.registeredWithPeerServices = true;
		BitField bitField = this.pieceDatabase.getPresentPieces();

		if (this.pieceDatabase.getInfo().isElastic()) {
			this.outboundQueue.sendHaveNoneMessage();
		} else {
			if (this.fastExtensionEnabled) {
				int pieceCount = bitField.cardinality();
				if (pieceCount == 0) {
					this.outboundQueue.sendHaveNoneMessage();
				} else if (pieceCount == this.pieceDatabase.getStorageDescriptor().getNumberOfPieces()) {
					this.outboundQueue.sendHaveAllMessage();
				} else {
					this.outboundQueue.sendBitfieldMessage (bitField);
				}
			} else {
				if (bitField.cardinality() > 0) {
					this.outboundQueue.sendBitfieldMessage (bitField);
				}
			}
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#keepAliveMessage()
	 */
	public void keepAliveMessage() {
		// Do nothing
		// The time of the last received data, which is implicitly updated by the receipt of a
		// keepalive, is consumed in #sendKeepaliveOrClose()
	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#chokeMessage(boolean)
	 */
	public void chokeMessage (boolean choked) {

		this.theyAreChoking = choked;

		this.outboundQueue.setRequestsPlugged (choked);
		if (this.theyAreChoking && !this.fastExtensionEnabled) {
			this.outboundQueue.requeueAllRequestMessages();
		}

		// We may need to send new requests. They will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#interestedMessage(boolean)
	 */
	public void interestedMessage (boolean interested) {

		this.theyAreInterested = interested;
		this.peerServices.adjustChoking (this.weAreChoking);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#haveMessage(int)
	 */
	public void haveMessage (int pieceIndex) throws IOException {

		if ((pieceIndex < 0) || (pieceIndex >= this.pieceDatabase.getStorageDescriptor().getNumberOfPieces())) {
			throw new IOException ("Invalid have message");
		}

		// If we were previously not interested in the remote peer and they announce a piece we
		// need, inform them of our interest and fill the request queue
		if (!this.remoteBitField.get (pieceIndex)) {
			this.remoteBitField.set (pieceIndex);
			if (this.peerServices.addAvailablePiece (this, pieceIndex) && (!this.weAreInterested)) {
				this.weAreInterested = true;
				this.outboundQueue.sendInterestedMessage (true);
			}
		}

		if (this.remoteBitField.cardinality() == PeerProtocolConstants.ALLOWED_FAST_THRESHOLD) {
			this.outboundQueue.clearAllowedFastPieces();
		}

		// We may need to send new requests; they will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#bitfieldMessage(byte[])
	 */
	public void bitfieldMessage (byte[] bitField) throws IOException {

		// Validate the bitfield
		try {
			this.remoteBitField = new BitField (bitField, this.remoteViewDescriptor.getNumberOfPieces());
		} catch (IllegalArgumentException e) {
			throw new IOException (e);
		}

		// Set our interest in the remote peer
		if (this.peerServices.addAvailablePieces (this)) {
			this.weAreInterested = true;
			this.outboundQueue.sendInterestedMessage (true);
		}

		// Send an Allowed Fast set if appropriate 
		if (
				   this.fastExtensionEnabled
				&& !this.pieceDatabase.getInfo().isElastic()
				&& this.remoteBitField.cardinality() < PeerProtocolConstants.ALLOWED_FAST_THRESHOLD
		   )
		{
			generateAndSendAllowedFastSet();
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#requestMessage(int, int, int)
	 */
	public void requestMessage (BlockDescriptor descriptor) throws IOException {

		// Validate the descriptor
		if (!validateBlockDescriptor (descriptor)) {
			throw new IOException ("Invalid request message");
		}

		if (this.pieceDatabase.havePiece (descriptor.getPieceNumber())) {

			// Queue the request if we are not choking; If we are choking,
			//   Base protocol:  Do nothing
			//   Fast extension: Queue the request if its piece is Allowed Fast, otherwise send an
			//                   explicit reject
			if (!this.weAreChoking) {
				this.outboundQueue.sendPieceMessage (descriptor);
			} else if (this.fastExtensionEnabled) {
				if (this.outboundQueue.isPieceAllowedFast (descriptor.getPieceNumber())) {
					this.outboundQueue.sendPieceMessage (descriptor);
				} else {
					this.outboundQueue.sendRejectRequestMessage (descriptor);
				}
			}

		} else {

			// TODO Semantics - This is necessary to support the Elastic extension, but is it the best semantic?
			if (this.fastExtensionEnabled) {
				this.outboundQueue.sendRejectRequestMessage (descriptor);
			} else {
				throw new IOException ("Piece " + descriptor.getPieceNumber() + " not present");
			}

		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#pieceMessage(int, int, byte[])
	 */
	public void pieceMessage (BlockDescriptor descriptor, byte[] data) throws IOException {

		if (this.pieceDatabase.getInfo().isMerkle()) {
			throw new IOException ("Ordinary piece received for Merkle torrent");
		}

		if (this.pieceDatabase.getInfo().isElastic()) {
			throw new IOException ("Ordinary piece received for Elastic torrent");
		}


		// Validate the descriptor (PeerProtocolParser ensures that the data is of the correct length)
		if (!validateBlockDescriptor (descriptor)) {
			throw new IOException ("Invalid piece message");
		}

		// Handle the block
		if (this.outboundQueue.requestReceived (descriptor)) {
			this.blockBytesReceivedCounter.add (descriptor.getLength());
			this.peerServices.handleBlock (this, descriptor, null, null, data);
		} else {
			if (!this.fastExtensionEnabled) {
				// Spam, or a request we cancelled. Can't tell the difference in the base protocol,
				// so do nothing
			} else {
				throw new IOException ("Unrequested piece received");
			}
			
		}

		// We may need to send new requests. They will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#cancelMessage(int, int, int)
	 */
	public void cancelMessage (BlockDescriptor descriptor) throws IOException {

		// Validate the descriptor
		if (!validateBlockDescriptor (descriptor)) {
			throw new IOException ("Invalid cancel message");
		}

		// Attempt to discard the piece from the outbound queue. If the piece was removed unsent,
		//   Base protocol: Do nothing
		//   Fast extension: Send an explicit reject
		boolean removed = this.outboundQueue.discardPieceMessage (descriptor);
		if (this.fastExtensionEnabled && removed) {
			this.outboundQueue.sendRejectRequestMessage (descriptor);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#suggestPieceMessage(int)
	 */
	public void suggestPieceMessage (int pieceNumber) throws IOException {

		if ((pieceNumber < 0) || (pieceNumber >= this.pieceDatabase.getStorageDescriptor().getNumberOfPieces())) {
			throw new IOException ("Invalid suggest piece message");
		}

		// The Fast Extension spec is silent on whether it is permissible for a peer to Suggest
		// Piece a piece they don't actually have (although it would be a pretty stupid thing to
		// do). We will simply ignore any such suggestions.

		if (this.remoteBitField.get (pieceNumber)) {
			this.peerServices.setPieceSuggested (this, pieceNumber);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#haveAllMessage()
	 */
	public void haveAllMessage() {

		// The remote bitfield is initially all zero, and PeerProtocolParser ensures this message
		// can only be the first message; invert the bitfield to set all bits
		this.remoteBitField.not();

		// Set our interest in the remote peer.
		if (this.peerServices.addAvailablePieces (this)) {
			this.weAreInterested = true;
			this.outboundQueue.sendInterestedMessage (true);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#haveNoneMessage()
	 */
	public void haveNoneMessage() {

		// The remote bitfield is initially all zero, so there's no need to do anything to it

		// Send an Allowed Fast set
		if (!this.pieceDatabase.getInfo().isElastic()) {
			generateAndSendAllowedFastSet();
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#rejectRequestMessage(org.itadaki.bobbin.peer.BlockDescriptor)
	 */
	public void rejectRequestMessage (BlockDescriptor descriptor) throws IOException {

		if (!this.outboundQueue.rejectReceived (descriptor)) {
			throw new IOException ("Reject received for unrequested piece");
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#allowedFastMessage(int)
	 */
	public void allowedFastMessage (int pieceNumber) throws IOException {

		if ((pieceNumber < 0) || (pieceNumber >= this.remoteBitField.length())) {
			throw new IOException ("Invalid allowed fast message");
		}

		// The Fast Extension spec explicitly allows peers to send Allowed Fast messages for pieces
		// they don't actually have. We drop any such messages here
		if (this.remoteBitField.get (pieceNumber)) {
			this.peerServices.setPieceAllowedFast (this, pieceNumber);
			this.outboundQueue.setRequestAllowedFast (pieceNumber);
		}

		// We may need to send new requests. They will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#extensionHandshakeMessage(java.util.Set, java.util.Set, org.itadaki.bobbin.bencode.BDictionary)
	 */
	public void extensionHandshakeMessage (Set<String> extensionsEnabled, Set<String> extensionsDisabled, BDictionary extra) throws IOException
	{

		this.remoteExtensions.addAll (extensionsEnabled);
		this.remoteExtensions.removeAll (extensionsDisabled);
		this.peerServices.enableDisablePeerExtensions (this, extensionsEnabled, extensionsDisabled, extra);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#extensionMessage(java.lang.String, byte[])
	 */
	public void extensionMessage (String identifier, byte[] data) throws IOException {

		this.peerServices.processExtensionMessage (this, identifier, data);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#merklePieceMessage(org.itadaki.bobbin.torrentdb.BlockDescriptor, byte[], byte[])
	 */
	public void merklePieceMessage (BlockDescriptor descriptor, byte[] hashChain, byte[] block) throws IOException {

		if (!this.pieceDatabase.getInfo().isMerkle()) {
			throw new IOException ("Merkle piece received for ordinary torrent");
		}

		// Validate the descriptor (PeerProtocolParser ensures that the data is of the correct length)
		if (!validateBlockDescriptor (descriptor)) {
			throw new IOException ("Invalid piece message");
		}

		// Handle the block
		if (this.outboundQueue.requestReceived (descriptor)) {
			this.blockBytesReceivedCounter.add (descriptor.getLength());
			this.peerServices.handleBlock (
					this,
					descriptor,
					null,
					new HashChain (this.pieceDatabase.getStorageDescriptor().getLength(), ByteBuffer.wrap (hashChain)), block
			);
		} else {
			if (!this.fastExtensionEnabled) {
				// Spam, or a request we cancelled. Can't tell the difference in the base protocol,
				// so do nothing
			} else {
				throw new IOException ("Unrequested piece received");
			}
		}

		// We may need to send new requests. They will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#elasticSignatureMessage(org.itadaki.bobbin.peer.ViewSignature)
	 */
	public void elasticSignatureMessage (ViewSignature viewSignature) throws IOException {

		if (viewSignature.getViewLength() > this.remoteViewDescriptor.getLength()) {
			this.remoteViewDescriptor = new StorageDescriptor (this.remoteViewDescriptor.getPieceSize(), viewSignature.getViewLength());
		}

		int pieceSize = this.pieceDatabase.getStorageDescriptor().getPieceSize();
		int viewNumPieces = (int)((viewSignature.getViewLength() + pieceSize - 1) / pieceSize);
		if (viewNumPieces > this.remoteBitField.length()) {
			this.remoteBitField.extend (viewNumPieces);
		}

		if (!this.peerServices.handleViewSignature (viewSignature)) {
			throw new IOException ("Signature failed verification");
		}

		if (this.remotePeerSignatures.size() > 1) {
			this.remotePeerSignatures.pollFirstEntry();
		}
		this.remotePeerSignatures.put (viewSignature.getViewLength(), viewSignature);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#elasticPieceMessage(long, org.itadaki.bobbin.torrentdb.BlockDescriptor, byte[], byte[])
	 */
	public void elasticPieceMessage (BlockDescriptor descriptor, Long viewLength, byte[] hashChain, byte[] block) throws IOException {

		if (!this.pieceDatabase.getInfo().isElastic()) {
			throw new IOException ("Elastic piece received for ordinary torrent");
		}

		// Validate the descriptor (PeerProtocolParser ensures that the data is of the correct length)
		if (!validateBlockDescriptor (descriptor)) {
			throw new IOException ("Invalid piece message");
		}

		// Handle the block
		if (this.outboundQueue.requestReceived (descriptor)) {
			if ((hashChain != null) && (!this.remotePeerSignatures.containsKey (viewLength))) {
				throw new IOException ("Invalid view length in piece");
			}
			this.blockBytesReceivedCounter.add (descriptor.getLength());
			this.peerServices.handleBlock (
					this,
					descriptor,
					hashChain == null ? null : this.remotePeerSignatures.get (viewLength),
					hashChain == null ? null : new HashChain (viewLength, ByteBuffer.wrap (hashChain)), block
			);
		} else {
			if (!this.fastExtensionEnabled) {
				// Spam, or a request we cancelled. Can't tell the difference in the base protocol,
				// so do nothing
			} else {
				throw new IOException ("Unrequested piece received");
			}
		}

		// We may need to send new requests. They will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#elasticBitfieldMessage(byte[])
	 */
	public void elasticBitfieldMessage (byte[] bitField) throws IOException {

		// TODO Temporary - to be replaced when new Elastic Bitfield format is decided
		bitfieldMessage (bitField);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.PeerProtocolConsumer#unknownMessage(int, byte[])
	 */
	public void unknownMessage (int messageID, byte[] messageBytes) {

		// Ignore it

	}


	/* ConnectionReadyListener interface */

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.connectionmanager.ConnectionReadyListener#connectionReady(org.itadaki.bobbin.connectionmanager.Connection, boolean, boolean)
	 */
	public void connectionReady (Connection connection, boolean readable, boolean writeable) {

		if (this.peerServices != null) {
			this.peerServices.lock();
		}

		try {

			if (readable) {
				int bytesRead = this.protocolParser.parseBytes (connection);
				this.protocolBytesReceivedCounter.add (bytesRead);
				if (bytesRead > 0) {
					this.lastDataReceivedTime = System.currentTimeMillis();
				}
			}

			if (this.registeredWithPeerServices && this.weAreInterested) {
				fillRequestQueue();
			}
	
			if (writeable) {
				int bytesWritten = this.outboundQueue.sendData();
				this.protocolBytesSentCounter.add (bytesWritten);
			}

		} catch (IOException e) {

			try {
				this.connection.close();
			} catch (IOException e1) {
				// Shouldn't happen, and nothing we can do if it does
			}

			if (this.peerServices != null) {
				this.peerServices.peerDisconnected (this);
			}

		}

		if (this.peerServices != null) {
			this.peerServices.unlock();
		}

	}


	/**
	 * Determines whether a block descriptor points to a valid region of the PieceDatabase and is no
	 * larger than the maximum allowed request
	 *
	 * @param blockDescriptor The descriptor to validate
	 * @return {@code true} if the descriptor is valid, otherwise {@code false}
	 */
	private boolean validateBlockDescriptor (BlockDescriptor blockDescriptor) {

		if (
			   (blockDescriptor.getPieceNumber() >= 0)
			&& (blockDescriptor.getPieceNumber () < this.pieceDatabase.getStorageDescriptor().getNumberOfPieces())
			&& (blockDescriptor.getOffset() >= 0
			&& (blockDescriptor.getLength() > 0))
			&& (blockDescriptor.getLength() <= PeerProtocolConstants.MAXIMUM_BLOCK_LENGTH)
			&& ((blockDescriptor.getOffset() + blockDescriptor.getLength()) <= this.pieceDatabase.getStorageDescriptor().getPieceLength (blockDescriptor.getPieceNumber())))
		{
			return true;
		}

		return false;

	}


	/**
	 * Generate and send appropriate Allowed Fast messages to the remotepeer
	 */
	private void generateAndSendAllowedFastSet() {

		byte[] remoteAddressBytes = this.connection.getRemoteAddress().getAddress();

		try {

			if (remoteAddressBytes.length == 4) {
				Set<Integer> allowedFastSet = new HashSet<Integer>();
				int numberOfPieces = this.pieceDatabase.getStorageDescriptor().getNumberOfPieces();

				remoteAddressBytes[3] = 0;
				byte[] infoHashBytes = this.infoHash.getBytes();
				byte[] hash = new byte[20];

				MessageDigest digest = MessageDigest.getInstance ("SHA");
				digest.update (remoteAddressBytes, 0, 4);
				digest.update (infoHashBytes, 0, infoHashBytes.length);
				digest.digest (hash, 0, 20);

				int numberAllowedFast = Math.min (PeerProtocolConstants.ALLOWED_FAST_THRESHOLD, numberOfPieces);

				while (allowedFastSet.size() < numberAllowedFast) {

					for (int i = 0; i < 5 && allowedFastSet.size() < numberAllowedFast; i++) {
						int j = i * 4;
						long y = (
								    (((long)hash[j] & 0xff) << 24) +
								  + ((hash[j+1] & 0xff) << 16) +
								  + ((hash[j+2] & 0xff) << 8) +
								  + (hash[j+3] & 0xff)
								) % numberOfPieces;
						allowedFastSet.add ((int)y);
					}

					digest.reset();
					digest.update (hash, 0, hash.length);
					digest.digest (hash, 0, 20);

				}

				this.outboundQueue.sendAllowedFastMessages (allowedFastSet);
			}

		} catch (GeneralSecurityException e) {
			// Shouldn't happen
			throw new InternalError (e.getMessage());
		}

	}


	/**
	 * Fills the request queue to the remote peer. If the {@link PeerServices} cannot supply any
	 * requests and there are none pending in the {@link PeerOutboundQueue}, signals the remote peer
	 * that we are not interested and updates our interest status
	 */
	private void fillRequestQueue() {

		int numRequests = this.outboundQueue.getRequestsNeeded();

		if (numRequests > 0) {
			List<BlockDescriptor> requests = this.peerServices.getRequests (this, numRequests, this.theyAreChoking);
			if (requests.size() > 0) {
				this.outboundQueue.sendRequestMessages (requests);
			} else {
				if (!this.theyAreChoking && !this.outboundQueue.hasOutstandingRequests()) {
					this.weAreInterested = false;
					this.outboundQueue.sendInterestedMessage (false);
				}
			}
		}

	}


	/**
	 * Completes the peer setup against the PeerServices, and sends a handshake to the remote peer.
	 * For an outbound connection this is performed early, in the constructor; for an inbound
	 * connection, this is performed late, after we have received the remote peer's handshake
	 * header and info hash.
	 */
	private void completeSetupAndHandshake() {

		this.pieceDatabase = this.peerServices.getPieceDatabase();

		StorageDescriptor initialDescriptor = this.pieceDatabase.getInfo().getStorageDescriptor();
		this.remoteBitField = new BitField (initialDescriptor.getNumberOfPieces());
		this.remoteViewDescriptor = initialDescriptor;
		this.infoHash = this.pieceDatabase.getInfo().getHash();
		this.outboundQueue = new PeerOutboundQueue (this.connection, this.pieceDatabase, this.blockBytesSentCounter);
		this.protocolBytesSentCounter.setParent (this.peerServices.getProtocolBytesSentCounter());
		this.protocolBytesReceivedCounter.setParent (this.peerServices.getProtocolBytesReceivedCounter());
		this.blockBytesSentCounter.setParent (this.peerServices.getBlockBytesSentCounter());
		this.blockBytesReceivedCounter.setParent (this.peerServices.getBlockBytesReceivedCounter());

		this.outboundQueue.sendHandshake (this.fastExtensionEnabled, this.extensionProtocolEnabled, this.infoHash,
				this.peerServices.getLocalPeerID());

	}


	/**
	 * Constructor for an inbound connection. The setup of a PeerHandler created in this way will be
	 * completed in {@link #handshakeInfoHash(InfoHash)} when we know which torrent we are supposed to
	 * be talking to the remote peer about
	 *  
	 * @param peerServicesProvider The {@link PeerServicesProvider} that will be asked to provide a
	 *        suitable PeerServices later
	 * @param connection The connection to perform network I/O on
	 */
	public PeerHandler (PeerServicesProvider peerServicesProvider, Connection connection) {

		this.peerServicesProvider = peerServicesProvider;
		this.connection = connection;
		this.protocolParser = new PeerProtocolParser (this, this.fastExtensionEnabled, this.extensionProtocolEnabled);
		this.connection.setListener (this);

		this.protocolBytesReceivedCounter.addCountedPeriod (PeerCoordinator.TWO_SECOND_PERIOD);
		this.protocolBytesSentCounter.addCountedPeriod (PeerCoordinator.TWO_SECOND_PERIOD);

	}


	/**
	 * Constructor for an outbound connection
	 * 
	 * @param peerServices The torrent's PeerServices
	 * @param connection The connection to perform network I/O on
	 */
	public PeerHandler (PeerServices peerServices, Connection connection) {

		this.peerServices = peerServices;
		this.connection = connection;
		this.protocolParser = new PeerProtocolParser (this, this.fastExtensionEnabled, this.extensionProtocolEnabled);
		this.connection.setListener (this);

		this.protocolBytesReceivedCounter.addCountedPeriod (PeerCoordinator.TWO_SECOND_PERIOD);
		this.protocolBytesSentCounter.addCountedPeriod (PeerCoordinator.TWO_SECOND_PERIOD);

		completeSetupAndHandshake();

	}


}