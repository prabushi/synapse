/*
 * Copyright 1999-2004 The Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *  
 */

package org.apache.sandesha2.msgprocessors;

import java.util.ArrayList;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.llom.OMElementImpl;
import org.apache.axiom.soap.SOAPBody;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.context.OperationContextFactory;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.AxisOperationFactory;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.transport.TransportSender;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sandesha2.RMMsgContext;
import org.apache.sandesha2.Sandesha2Constants;
import org.apache.sandesha2.SandeshaException;
import org.apache.sandesha2.client.SandeshaClientConstants;
import org.apache.sandesha2.client.SandeshaListener;
import org.apache.sandesha2.i18n.SandeshaMessageHelper;
import org.apache.sandesha2.i18n.SandeshaMessageKeys;
import org.apache.sandesha2.security.SecurityManager;
import org.apache.sandesha2.security.SecurityToken;
import org.apache.sandesha2.storage.StorageManager;
import org.apache.sandesha2.storage.beanmanagers.CreateSeqBeanMgr;
import org.apache.sandesha2.storage.beanmanagers.InvokerBeanMgr;
import org.apache.sandesha2.storage.beanmanagers.NextMsgBeanMgr;
import org.apache.sandesha2.storage.beanmanagers.SenderBeanMgr;
import org.apache.sandesha2.storage.beanmanagers.SequencePropertyBeanMgr;
import org.apache.sandesha2.storage.beans.CreateSeqBean;
import org.apache.sandesha2.storage.beans.InvokerBean;
import org.apache.sandesha2.storage.beans.NextMsgBean;
import org.apache.sandesha2.storage.beans.SenderBean;
import org.apache.sandesha2.storage.beans.SequencePropertyBean;
import org.apache.sandesha2.transport.Sandesha2TransportOutDesc;
import org.apache.sandesha2.util.AcknowledgementManager;
import org.apache.sandesha2.util.FaultManager;
import org.apache.sandesha2.util.MsgInitializer;
import org.apache.sandesha2.util.RMMsgCreator;
import org.apache.sandesha2.util.SOAPAbstractFactory;
import org.apache.sandesha2.util.SandeshaUtil;
import org.apache.sandesha2.util.SequenceManager;
import org.apache.sandesha2.util.SpecSpecificConstants;
import org.apache.sandesha2.wsrm.AckRequested;
import org.apache.sandesha2.wsrm.CreateSequence;
import org.apache.sandesha2.wsrm.Identifier;
import org.apache.sandesha2.wsrm.LastMessage;
import org.apache.sandesha2.wsrm.MessageNumber;
import org.apache.sandesha2.wsrm.Sequence;
import org.apache.sandesha2.wsrm.SequenceAcknowledgement;
import org.apache.sandesha2.wsrm.SequenceOffer;

/**
 * Responsible for processing an incoming Application message.
 */

public class ApplicationMsgProcessor implements MsgProcessor {

	private boolean letInvoke = false;

	private static final Log log = LogFactory.getLog(ApplicationMsgProcessor.class);

	public void processInMessage(RMMsgContext rmMsgCtx) throws SandeshaException {
		if (log.isDebugEnabled())
			log.debug("Enter: ApplicationMsgProcessor::processInMessage");

		// TODO process embedded ack requests
		AckRequested ackRequested = (AckRequested) rmMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.ACK_REQUEST);
		if (ackRequested != null) {
			ackRequested.setMustUnderstand(false);
			rmMsgCtx.addSOAPEnvelope();
		}

		// Processing the application message.
		MessageContext msgCtx = rmMsgCtx.getMessageContext();
		if (msgCtx == null) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.msgContextNotSetInbound);
			log.debug(message);
			throw new SandeshaException(message);
		}

		if (rmMsgCtx.getProperty(Sandesha2Constants.APPLICATION_PROCESSING_DONE) != null
				&& rmMsgCtx.getProperty(Sandesha2Constants.APPLICATION_PROCESSING_DONE).equals("true")) {
			return;
		}

		StorageManager storageManager = SandeshaUtil.getSandeshaStorageManager(msgCtx.getConfigurationContext(),msgCtx.getConfigurationContext().getAxisConfiguration());
		SequencePropertyBeanMgr seqPropMgr = storageManager.getSequencePropertyBeanMgr();
		Sequence sequence = (Sequence) rmMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.SEQUENCE);
		String sequenceId = sequence.getIdentifier().getIdentifier();
		
		// Check that both the Sequence header and message body have been secured properly
		SequencePropertyBean tokenBean = seqPropMgr.retrieve(sequenceId, Sandesha2Constants.SequenceProperties.SECURITY_TOKEN);
		if(tokenBean != null) {
			SecurityManager secManager = SandeshaUtil.getSecurityManager(msgCtx.getConfigurationContext());
			OMElement body = msgCtx.getEnvelope().getBody();
			SecurityToken token = secManager.recoverSecurityToken(tokenBean.getValue());
			
			//TODO get the element from the SOAP Envelope
//			secManager.checkProofOfPossession(token, sequence.getOMElement(), msgCtx);
			
			secManager.checkProofOfPossession(token, body, msgCtx);
		}
		
		//RM will not send sync responses. If sync acks are there this will be
		// made true again later.
		if (rmMsgCtx.getMessageContext().getOperationContext() != null) {
			rmMsgCtx.getMessageContext().getOperationContext().setProperty(Constants.RESPONSE_WRITTEN,
					Constants.VALUE_FALSE);
		}

		FaultManager faultManager = new FaultManager();
		RMMsgContext faultMessageContext = faultManager.checkForLastMsgNumberExceeded(rmMsgCtx, storageManager);
		if (faultMessageContext != null) {
			ConfigurationContext configurationContext = msgCtx.getConfigurationContext();
			AxisEngine engine = new AxisEngine(configurationContext);

			try {
				engine.sendFault(faultMessageContext.getMessageContext());
			} catch (AxisFault e) {
				throw new SandeshaException(SandeshaMessageHelper.getMessage(SandeshaMessageKeys.couldNotSendFault, e
						.toString()));
			}

			msgCtx.pause();
			return;
		}

		// setting acked msg no range
		ConfigurationContext configCtx = rmMsgCtx.getMessageContext().getConfigurationContext();
		if (configCtx == null) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.configContextNotSet);
			log.debug(message);
			throw new SandeshaException(message);
		}

		faultMessageContext = faultManager.checkForUnknownSequence(rmMsgCtx, sequenceId, storageManager);
		if (faultMessageContext != null) {
			ConfigurationContext configurationContext = msgCtx.getConfigurationContext();
			AxisEngine engine = new AxisEngine(configurationContext);

			try {
				engine.send(faultMessageContext.getMessageContext());
			} catch (AxisFault e) {
				throw new SandeshaException(SandeshaMessageHelper.getMessage(SandeshaMessageKeys.couldNotSendFault, e
						.toString()));
			}

			msgCtx.pause();
			return;
		}

		// setting mustUnderstand to false.
		sequence.setMustUnderstand(false);
		rmMsgCtx.addSOAPEnvelope();

		// throwing a fault if the sequence is closed.
		faultMessageContext = faultManager.checkForSequenceClosed(rmMsgCtx, sequenceId, storageManager);
		if (faultMessageContext != null) {
			ConfigurationContext configurationContext = msgCtx.getConfigurationContext();
			AxisEngine engine = new AxisEngine(configurationContext);

			try {
				engine.sendFault(faultMessageContext.getMessageContext());
			} catch (AxisFault e) {
				throw new SandeshaException(SandeshaMessageHelper.getMessage(SandeshaMessageKeys.couldNotSendFault, e
						.toString()));
			}

			return;
		}

		// updating the last activated time of the sequence.
		SequenceManager.updateLastActivatedTime(sequenceId, storageManager);

		SequencePropertyBean msgsBean = seqPropMgr.retrieve(sequenceId,
				Sandesha2Constants.SequenceProperties.SERVER_COMPLETED_MESSAGES);

		long msgNo = sequence.getMessageNumber().getMessageNumber();
		if (msgNo == 0) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.invalidMsgNumber, Long
					.toString(msgNo));
			log.debug(message);
			throw new SandeshaException(message);
		}

		String key = SandeshaUtil.getUUID(); // key to store the message.

		// updating the Highest_In_Msg_No property which gives the highest
		// message number retrieved from this sequence.
		String highetsInMsgNoStr = SandeshaUtil.getSequenceProperty(sequenceId,
				Sandesha2Constants.SequenceProperties.HIGHEST_IN_MSG_NUMBER, storageManager);
		String highetsInMsgKey = SandeshaUtil.getSequenceProperty(sequenceId,
				Sandesha2Constants.SequenceProperties.HIGHEST_IN_MSG_KEY, storageManager);
		if (highetsInMsgKey == null)
			highetsInMsgKey = SandeshaUtil.getUUID();

		long highestInMsgNo = 0;
		if (highetsInMsgNoStr != null) {
			highestInMsgNo = Long.parseLong(highetsInMsgNoStr);
		}

		if (msgNo > highestInMsgNo) {
			highestInMsgNo = msgNo;

			String str = new Long(msgNo).toString();
			SequencePropertyBean highestMsgNoBean = new SequencePropertyBean(sequenceId,
					Sandesha2Constants.SequenceProperties.HIGHEST_IN_MSG_NUMBER, str);
			SequencePropertyBean highestMsgKeyBean = new SequencePropertyBean(sequenceId,
					Sandesha2Constants.SequenceProperties.HIGHEST_IN_MSG_KEY, highetsInMsgKey);

			// storing the new message as the highest in message.
			storageManager.removeMessageContext(highetsInMsgKey);
			storageManager.storeMessageContext(highetsInMsgKey, msgCtx);

			if (highetsInMsgNoStr != null) {
				seqPropMgr.update(highestMsgNoBean);
				seqPropMgr.update(highestMsgKeyBean);
			} else {
				seqPropMgr.insert(highestMsgNoBean);
				seqPropMgr.insert(highestMsgKeyBean);
			}
		}

		String messagesStr = "";
		if (msgsBean != null)
			messagesStr = (String) msgsBean.getValue();
		else {
			msgsBean = new SequencePropertyBean();
			msgsBean.setSequenceID(sequenceId);
			msgsBean.setName(Sandesha2Constants.SequenceProperties.SERVER_COMPLETED_MESSAGES);
			msgsBean.setValue(messagesStr);
		}

		if (msgNoPresentInList(messagesStr, msgNo)
				&& (Sandesha2Constants.QOS.InvocationType.DEFAULT_INVOCATION_TYPE == Sandesha2Constants.QOS.InvocationType.EXACTLY_ONCE)) {
			// this is a duplicate message and the invocation type is
			// EXACTLY_ONCE.
			rmMsgCtx.pause();
		}

		if (messagesStr != "" && messagesStr != null)
			messagesStr = messagesStr + "," + Long.toString(msgNo);
		else
			messagesStr = Long.toString(msgNo);

		msgsBean.setValue(messagesStr);
		seqPropMgr.update(msgsBean);

		// Pause the messages bean if not the right message to invoke.
		NextMsgBeanMgr mgr = storageManager.getNextMsgBeanMgr();
		NextMsgBean bean = mgr.retrieve(sequenceId);

		if (bean == null) {
			throw new SandeshaException(SandeshaMessageHelper.getMessage(SandeshaMessageKeys.cannotFindSequence,
					sequenceId));
		}

		InvokerBeanMgr storageMapMgr = storageManager.getStorageMapBeanMgr();

		// inorder invocation is still a global property
		boolean inOrderInvocation = SandeshaUtil.getPropertyBean(
				msgCtx.getConfigurationContext().getAxisConfiguration()).isInOrder();

		if (inOrderInvocation) {

			SequencePropertyBean incomingSequenceListBean = (SequencePropertyBean) seqPropMgr.retrieve(
					Sandesha2Constants.SequenceProperties.ALL_SEQUENCES,
					Sandesha2Constants.SequenceProperties.INCOMING_SEQUENCE_LIST);

			if (incomingSequenceListBean == null) {
				ArrayList incomingSequenceList = new ArrayList();
				incomingSequenceListBean = new SequencePropertyBean();
				incomingSequenceListBean.setSequenceID(Sandesha2Constants.SequenceProperties.ALL_SEQUENCES);
				incomingSequenceListBean.setName(Sandesha2Constants.SequenceProperties.INCOMING_SEQUENCE_LIST);
				incomingSequenceListBean.setValue(incomingSequenceList.toString());

				// this get inserted before
				seqPropMgr.insert(incomingSequenceListBean);
			}

			ArrayList incomingSequenceList = SandeshaUtil.getArrayListFromString(incomingSequenceListBean.getValue());

			// Adding current sequence to the incoming sequence List.
			if (!incomingSequenceList.contains(sequenceId)) {
				incomingSequenceList.add(sequenceId);

				// saving the property.
				incomingSequenceListBean.setValue(incomingSequenceList.toString());
				seqPropMgr.update(incomingSequenceListBean);
			}

			// saving the message.
			try {
				storageManager.storeMessageContext(key, rmMsgCtx.getMessageContext());
				storageMapMgr.insert(new InvokerBean(key, msgNo, sequenceId));

				// This will avoid performing application processing more
				// than
				// once.
				rmMsgCtx.setProperty(Sandesha2Constants.APPLICATION_PROCESSING_DONE, "true");

			} catch (Exception ex) {
				throw new SandeshaException(ex.getMessage());
			}

			// pause the message
			rmMsgCtx.pause();

			// Starting the invoker if stopped.
			SandeshaUtil.startInvokerForTheSequence(msgCtx.getConfigurationContext(), sequenceId);

		}

		// Sending acknowledgements
		sendAckIfNeeded(rmMsgCtx, messagesStr, storageManager);

		if (log.isDebugEnabled())
			log.debug("Exit: ApplicationMsgProcessor::processInMessage");
	}

	// TODO convert following from INT to LONG
	private boolean msgNoPresentInList(String list, long no) {
		String[] msgStrs = list.split(",");

		int l = msgStrs.length;

		for (int i = 0; i < l; i++) {
			if (msgStrs[i].equals(Long.toString(no)))
				return true;
		}

		return false;
	}

	public void sendAckIfNeeded(RMMsgContext rmMsgCtx, String messagesStr, StorageManager storageManager)
			throws SandeshaException {

		if (log.isDebugEnabled())
			log.debug("Enter: ApplicationMsgProcessor::sendAckIfNeeded");

		Sequence sequence = (Sequence) rmMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.SEQUENCE);
		String sequenceId = sequence.getIdentifier().getIdentifier();
		ConfigurationContext configCtx = rmMsgCtx.getMessageContext().getConfigurationContext();
		if (configCtx == null)
			throw new SandeshaException(SandeshaMessageHelper.getMessage(SandeshaMessageKeys.configContextNotSet));

		AckRequested ackRequested = (AckRequested) rmMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.ACK_REQUEST);

		if (ackRequested != null) {
			// setting mustundestand=false for the ackRequested header block.
			ackRequested.setMustUnderstand(false);
			rmMsgCtx.addSOAPEnvelope();
		}

		RMMsgContext ackRMMessage = AcknowledgementManager.generateAckMessage(rmMsgCtx, sequenceId, storageManager);

		AxisEngine engine = new AxisEngine(configCtx);

		try {
			engine.send(ackRMMessage.getMessageContext());
		} catch (AxisFault e) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.couldNotSendAck, sequenceId, e
					.toString());
			throw new SandeshaException(message, e);
		}

		if (log.isDebugEnabled())
			log.debug("Exit: ApplicationMsgProcessor::sendAckIfNeeded");
	}

	public void processOutMessage(RMMsgContext rmMsgCtx) throws SandeshaException {
		if (log.isDebugEnabled())
			log.debug("Enter: ApplicationMsgProcessor::processOutMessage");

		MessageContext msgContext = rmMsgCtx.getMessageContext();
		ConfigurationContext configContext = msgContext.getConfigurationContext();

		// setting the Fault callback
		SandeshaListener faultCallback = (SandeshaListener) msgContext.getOptions().getProperty(
				SandeshaClientConstants.SANDESHA_LISTENER);
		if (faultCallback != null) {
			OperationContext operationContext = msgContext.getOperationContext();
			if (operationContext != null) {
				operationContext.setProperty(SandeshaClientConstants.SANDESHA_LISTENER, faultCallback);
			}
		}

		StorageManager storageManager = SandeshaUtil.getSandeshaStorageManager(configContext, configContext
				.getAxisConfiguration());
		SequencePropertyBeanMgr seqPropMgr = storageManager.getSequencePropertyBeanMgr();

		boolean serverSide = msgContext.isServerSide();

		// setting message Id if null
		if (msgContext.getMessageID() == null)
			msgContext.setMessageID(SandeshaUtil.getUUID());

		// find internal sequence id
		String internalSequenceId = null;

		String storageKey = SandeshaUtil.getUUID(); // the key which will be
													// used to store this
													// message.

		/*
		 * Internal sequence id is the one used to refer to the sequence (since
		 * actual sequence id is not available when first msg arrives) server
		 * side - a derivation of the sequenceId of the incoming sequence client
		 * side - a derivation of wsaTo & SeequenceKey
		 */

		boolean lastMessage = false;
		if (serverSide) {
			// getting the request message and rmMessage.
			MessageContext reqMsgCtx;
			try {
				reqMsgCtx = msgContext.getOperationContext().getMessageContext(
						OperationContextFactory.MESSAGE_LABEL_IN_VALUE);
			} catch (AxisFault e) {
				throw new SandeshaException(e);
			}

			RMMsgContext requestRMMsgCtx = MsgInitializer.initializeMessage(reqMsgCtx);

			Sequence reqSequence = (Sequence) requestRMMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.SEQUENCE);
			if (reqSequence == null) {
				String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.seqPartIsNull);
				log.debug(message);
				throw new SandeshaException(message);
			}

			String incomingSeqId = reqSequence.getIdentifier().getIdentifier();
			if (incomingSeqId == null || incomingSeqId == "") {
				String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.incomingSequenceNotValidID, "''"
						+ incomingSeqId + "''");
				log.debug(message);
				throw new SandeshaException(message);
			}

			long requestMsgNo = reqSequence.getMessageNumber().getMessageNumber();

			internalSequenceId = SandeshaUtil.getOutgoingSideInternalSequenceID(incomingSeqId);

			// deciding weather the last message.
			String requestLastMsgNoStr = SandeshaUtil.getSequenceProperty(incomingSeqId,
					Sandesha2Constants.SequenceProperties.LAST_IN_MESSAGE_NO, storageManager);
			if (requestLastMsgNoStr != null) {
				long requestLastMsgNo = Long.parseLong(requestLastMsgNoStr);
				if (requestLastMsgNo == requestMsgNo)
					lastMessage = true;
			}

		} else {
			// set the internal sequence id for the client side.
			EndpointReference toEPR = msgContext.getTo();
			if (toEPR == null || toEPR.getAddress() == null || "".equals(toEPR.getAddress())) {
				String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.toEPRNotValid, null);
				log.debug(message);
				throw new SandeshaException(message);
			}

			String to = toEPR.getAddress();
			String sequenceKey = (String) msgContext.getProperty(SandeshaClientConstants.SEQUENCE_KEY);
			internalSequenceId = SandeshaUtil.getInternalSequenceID(to, sequenceKey);

			String lastAppMessage = (String) msgContext.getProperty(SandeshaClientConstants.LAST_MESSAGE);
			if (lastAppMessage != null && "true".equals(lastAppMessage))
				lastMessage = true;
		}

		/*
		 * checking weather the user has given the messageNumber (most of the
		 * cases this will not be the case where the system will generate the
		 * message numbers
		 */

		// User should set it as a long object.
		Long messageNumberLng = (Long) msgContext.getProperty(SandeshaClientConstants.MESSAGE_NUMBER);

		long givenMessageNumber = -1;
		if (messageNumberLng != null) {
			givenMessageNumber = messageNumberLng.longValue();
			if (givenMessageNumber <= 0) {
				throw new SandeshaException(SandeshaMessageHelper.getMessage(
						SandeshaMessageKeys.msgNumberMustBeLargerThanZero, Long.toString(givenMessageNumber)));
			}
		}

		// the message number that was last used.
		long systemMessageNumber = getPreviousMsgNo(configContext, internalSequenceId, storageManager);

		// The number given by the user has to be larger than the last stored
		// number.
		if (givenMessageNumber > 0 && givenMessageNumber <= systemMessageNumber) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.msgNumberNotLargerThanLastMsg, Long
					.toString(givenMessageNumber));
			throw new SandeshaException(message);
		}

		// Finding the correct message number.
		long messageNumber = -1;
		if (givenMessageNumber > 0) // if given message number is valid use it.
									// (this is larger than the last stored due
									// to the last check)
			messageNumber = givenMessageNumber;
		else if (systemMessageNumber > 0) { // if system message number is valid
											// use it.
			messageNumber = systemMessageNumber + 1;
		} else { // This is the fist message (systemMessageNumber = -1)
			messageNumber = 1;
		}

		// A dummy message is a one which will not be processed as a actual
		// application message.
		// The RM handlers will simply let these go.
		String dummyMessageString = (String) msgContext.getOptions().getProperty(SandeshaClientConstants.DUMMY_MESSAGE);
		boolean dummyMessage = false;
		if (dummyMessageString != null && Sandesha2Constants.VALUE_TRUE.equals(dummyMessageString))
			dummyMessage = true;

		// saving the used message number
		if (!dummyMessage)
			setNextMsgNo(configContext, internalSequenceId, messageNumber, storageManager);

		// set this as the response highest message.
		SequencePropertyBean responseHighestMsgBean = new SequencePropertyBean(internalSequenceId,
				Sandesha2Constants.SequenceProperties.HIGHEST_OUT_MSG_NUMBER, new Long(messageNumber).toString());
		seqPropMgr.insert(responseHighestMsgBean);

		if (lastMessage) {

			SequencePropertyBean responseHighestMsgKeyBean = new SequencePropertyBean(internalSequenceId,
					Sandesha2Constants.SequenceProperties.HIGHEST_OUT_MSG_KEY, storageKey);

			SequencePropertyBean responseLastMsgKeyBean = new SequencePropertyBean(internalSequenceId,
					Sandesha2Constants.SequenceProperties.LAST_OUT_MESSAGE_NO, new Long(messageNumber).toString());

			seqPropMgr.insert(responseHighestMsgKeyBean);
			seqPropMgr.insert(responseLastMsgKeyBean);
		}

		boolean sendCreateSequence = false;

		SequencePropertyBean outSeqBean = seqPropMgr.retrieve(internalSequenceId,
				Sandesha2Constants.SequenceProperties.OUT_SEQUENCE_ID);

		// setting async ack endpoint for the server side. (if present)
		if (serverSide) {
			String incomingSequenceID = SandeshaUtil.getServerSideIncomingSeqIdFromInternalSeqId(internalSequenceId);
			SequencePropertyBean incomingToBean = seqPropMgr.retrieve(incomingSequenceID,
					Sandesha2Constants.SequenceProperties.TO_EPR);
			if (incomingToBean != null) {
				String incomingTo = incomingToBean.getValue();
				msgContext.setProperty(SandeshaClientConstants.AcksTo, incomingTo);
			}
		}

		// FINDING THE SPEC VERSION
		String specVersion = null;
		if (msgContext.isServerSide()) {
			// in the server side, get the RM version from the request sequence.
			MessageContext requestMessageContext;
			try {
				requestMessageContext = msgContext.getOperationContext().getMessageContext(
						AxisOperationFactory.MESSAGE_LABEL_IN_VALUE);
			} catch (AxisFault e) {
				throw new SandeshaException(e);
			}

			if (requestMessageContext == null) {
				throw new SandeshaException(SandeshaMessageHelper.getMessage(SandeshaMessageKeys.requestMsgContextNull));
			}

			RMMsgContext requestRMMsgCtx = MsgInitializer.initializeMessage(requestMessageContext);
			Sequence sequence = (Sequence) requestRMMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.SEQUENCE);

			String requestSequenceID = sequence.getIdentifier().getIdentifier();
			SequencePropertyBean specVersionBean = seqPropMgr.retrieve(requestSequenceID,
					Sandesha2Constants.SequenceProperties.RM_SPEC_VERSION);
			if (specVersionBean == null) {
				throw new SandeshaException(SandeshaMessageHelper.getMessage(
						SandeshaMessageKeys.specVersionPropertyNotAvailable, requestSequenceID));
			}

			specVersion = specVersionBean.getValue();
		} else {
			// in the client side, user will set the RM version.
			specVersion = (String) msgContext.getProperty(SandeshaClientConstants.RM_SPEC_VERSION);
		}

		if (specVersion == null)
			specVersion = SpecSpecificConstants.getDefaultSpecVersion(); // TODO
																			// change
																			// the
																			// default
																			// to
																			// v1_1.

		if (messageNumber == 1) {
			if (outSeqBean == null) { // out sequence will be set for the
										// server side, in the case of an offer.
				sendCreateSequence = true; // message number being one and not
											// having an out sequence, implies
											// that a create sequence has to be
											// send.
			}

			// if fist message - setup the sending side sequence - both for the
			// server and the client sides
			SequenceManager.setupNewClientSequence(msgContext, internalSequenceId, specVersion, storageManager);
		}

		ServiceContext serviceContext = msgContext.getServiceContext();
		OperationContext operationContext = msgContext.getOperationContext();

		// SENDING THE CREATE SEQUENCE.
		if (sendCreateSequence) {
			SequencePropertyBean responseCreateSeqAdded = seqPropMgr.retrieve(internalSequenceId,
					Sandesha2Constants.SequenceProperties.OUT_CREATE_SEQUENCE_SENT);

			String addressingNamespaceURI = SandeshaUtil.getSequenceProperty(internalSequenceId,
					Sandesha2Constants.SequenceProperties.ADDRESSING_NAMESPACE_VALUE, storageManager);
			String anonymousURI = SpecSpecificConstants.getAddressingAnonymousURI(addressingNamespaceURI);

			if (responseCreateSeqAdded == null) {
				responseCreateSeqAdded = new SequencePropertyBean(internalSequenceId,
						Sandesha2Constants.SequenceProperties.OUT_CREATE_SEQUENCE_SENT, "true");
				seqPropMgr.insert(responseCreateSeqAdded);

				String acksTo = null;
				if (serviceContext != null)
					acksTo = (String) msgContext.getProperty(SandeshaClientConstants.AcksTo);

				if (msgContext.isServerSide()) {
					// we do not set acksTo value to anonymous when the create
					// sequence is send from the server.
					MessageContext requestMessage;
					try {
						requestMessage = operationContext
								.getMessageContext(OperationContextFactory.MESSAGE_LABEL_IN_VALUE);
					} catch (AxisFault e) {
						throw new SandeshaException(e);
					}

					if (requestMessage == null) {
						String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.requestMsgNotPresent);
						log.debug(message);
						throw new SandeshaException(message);
					}
					acksTo = requestMessage.getTo().getAddress();

				} else {
					if (acksTo == null)
						acksTo = anonymousURI;
				}

				if (!anonymousURI.equals(acksTo) && !serverSide) {
					String transportIn = (String) configContext // TODO verify
							.getProperty(MessageContext.TRANSPORT_IN);
					if (transportIn == null)
						transportIn = org.apache.axis2.Constants.TRANSPORT_HTTP;
				} else if (acksTo == null && serverSide) {
					String incomingSequencId = SandeshaUtil
							.getServerSideIncomingSeqIdFromInternalSeqId(internalSequenceId);
					SequencePropertyBean bean = seqPropMgr.retrieve(incomingSequencId,
							Sandesha2Constants.SequenceProperties.REPLY_TO_EPR);
					if (bean != null) {
						EndpointReference acksToEPR = new EndpointReference(bean.getValue());
						if (acksToEPR != null)
							acksTo = (String) acksToEPR.getAddress();
					}
				} else if (anonymousURI.equals(acksTo)) {
					// set transport in.
					Object trIn = msgContext.getProperty(MessageContext.TRANSPORT_IN);
					if (trIn == null) {
						// TODO
					}
				}
				addCreateSequenceMessage(rmMsgCtx, internalSequenceId, acksTo, storageManager);
			}
		}

		SOAPEnvelope env = rmMsgCtx.getSOAPEnvelope();
		if (env == null) {
			SOAPEnvelope envelope = SOAPAbstractFactory.getSOAPFactory(SandeshaUtil.getSOAPVersion(env))
					.getDefaultEnvelope();
			rmMsgCtx.setSOAPEnvelop(envelope);
		}

		SOAPBody soapBody = rmMsgCtx.getSOAPEnvelope().getBody();
		if (soapBody == null) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.soapBodyNotPresent);
			log.debug(message);
			throw new SandeshaException(message);
		}

		String messageId1 = SandeshaUtil.getUUID();
		if (rmMsgCtx.getMessageId() == null) {
			rmMsgCtx.setMessageId(messageId1);
		}

		if (serverSide) {
			// let the request end with 202 if a ack has not been
			// written in the incoming thread.

			MessageContext reqMsgCtx = null;
			try {
				reqMsgCtx = msgContext.getOperationContext().getMessageContext(
						OperationContextFactory.MESSAGE_LABEL_IN_VALUE);
			} catch (AxisFault e) {
				throw new SandeshaException(e);
			}

			if (reqMsgCtx.getProperty(Sandesha2Constants.ACK_WRITTEN) == null
					|| !"true".equals(reqMsgCtx.getProperty(Sandesha2Constants.ACK_WRITTEN)))
				reqMsgCtx.getOperationContext().setProperty(org.apache.axis2.Constants.RESPONSE_WRITTEN, "false");
		}

		EndpointReference toEPR = msgContext.getTo();
		if (toEPR == null) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.toEPRNotValid, null);
			log.debug(message);
			throw new SandeshaException(message);
		}

		// setting default actions.
		String to = toEPR.getAddress();
		String operationName = msgContext.getOperationContext().getAxisOperation().getName().getLocalPart();
		if (msgContext.getWSAAction() == null) {
			msgContext.setWSAAction(to + "/" + operationName);
		}
		if (msgContext.getSoapAction() == null) {
			msgContext.setSoapAction("\"" + to + "/" + operationName + "\"");
		}

		// processing the response if not an dummy.
		if (!dummyMessage)
			processResponseMessage(rmMsgCtx, internalSequenceId, messageNumber, storageKey, storageManager);

		msgContext.pause(); // the execution will be stopped.

		if (log.isDebugEnabled())
			log.debug("Exit: ApplicationMsgProcessor::processOutMessage");
	}

	private void addCreateSequenceMessage(RMMsgContext applicationRMMsg, String internalSequenceId, String acksTo,
			StorageManager storageManager) throws SandeshaException {

		if (log.isDebugEnabled())
			log.debug("Enter: ApplicationMsgProcessor::addCreateSequenceMessage, " + internalSequenceId);

		MessageContext applicationMsg = applicationRMMsg.getMessageContext();
		ConfigurationContext configCtx = applicationMsg.getConfigurationContext();

		// generating a new create sequeuce message.
		RMMsgContext createSeqRMMessage = RMMsgCreator.createCreateSeqMsg(applicationRMMsg, internalSequenceId, acksTo,
				storageManager);

		createSeqRMMessage.setFlow(MessageContext.OUT_FLOW);
		CreateSequence createSequencePart = (CreateSequence) createSeqRMMessage
				.getMessagePart(Sandesha2Constants.MessageParts.CREATE_SEQ);

		SequencePropertyBeanMgr seqPropMgr = storageManager.getSequencePropertyBeanMgr();
		CreateSeqBeanMgr createSeqMgr = storageManager.getCreateSeqBeanMgr();
		SenderBeanMgr retransmitterMgr = storageManager.getRetransmitterBeanMgr();

		SequenceOffer offer = createSequencePart.getSequenceOffer();
		if (offer != null) {
			String offeredSequenceId = offer.getIdentifer().getIdentifier();

			SequencePropertyBean offeredSequenceBean = new SequencePropertyBean();
			offeredSequenceBean.setName(Sandesha2Constants.SequenceProperties.OFFERED_SEQUENCE);
			offeredSequenceBean.setSequenceID(internalSequenceId);
			offeredSequenceBean.setValue(offeredSequenceId);

			seqPropMgr.insert(offeredSequenceBean);
		}

		MessageContext createSeqMsg = createSeqRMMessage.getMessageContext();
		createSeqMsg.setRelationships(null); // create seq msg does not
												// relateTo anything

		CreateSeqBean createSeqBean = new CreateSeqBean(internalSequenceId, createSeqMsg.getMessageID(), null);
		SecurityToken token = (SecurityToken) createSeqRMMessage.getProperty(Sandesha2Constants.SequenceProperties.SECURITY_TOKEN);
		if(token != null) {
			SecurityManager secManager = SandeshaUtil.getSecurityManager(configCtx);
			createSeqBean.setSecurityTokenData(secManager.getTokenRecoveryData(token));
		}
		
		createSeqMgr.insert(createSeqBean);

		String addressingNamespaceURI = SandeshaUtil.getSequenceProperty(internalSequenceId,
				Sandesha2Constants.SequenceProperties.ADDRESSING_NAMESPACE_VALUE, storageManager);
		String anonymousURI = SpecSpecificConstants.getAddressingAnonymousURI(addressingNamespaceURI);

		if (createSeqMsg.getReplyTo() == null)
			createSeqMsg.setReplyTo(new EndpointReference(anonymousURI));

		String key = SandeshaUtil.getUUID(); // the key used to store the
												// create sequence message.

		SenderBean createSeqEntry = new SenderBean();
		createSeqEntry.setMessageContextRefKey(key);
		createSeqEntry.setTimeToSend(System.currentTimeMillis());
		createSeqEntry.setMessageID(createSeqRMMessage.getMessageId());
		createSeqEntry.setInternalSequenceID(internalSequenceId);
		// this will be set to true in the sender
		createSeqEntry.setSend(true);

		createSeqMsg.setProperty(Sandesha2Constants.QUALIFIED_FOR_SENDING, Sandesha2Constants.VALUE_FALSE);
		createSeqEntry.setMessageType(Sandesha2Constants.MessageTypes.CREATE_SEQ);
		retransmitterMgr.insert(createSeqEntry);

		storageManager.storeMessageContext(key, createSeqMsg); // storing the
																// message.

		// message will be stored in the Sandesha2TransportSender
		createSeqMsg.setProperty(Sandesha2Constants.MESSAGE_STORE_KEY, key);

		TransportOutDescription transportOut = createSeqMsg.getTransportOut();

		createSeqMsg.setProperty(Sandesha2Constants.ORIGINAL_TRANSPORT_OUT_DESC, transportOut);
		createSeqMsg.setProperty(Sandesha2Constants.SET_SEND_TO_TRUE, Sandesha2Constants.VALUE_TRUE);
		createSeqMsg.setProperty(Sandesha2Constants.MESSAGE_STORE_KEY, key);

		Sandesha2TransportOutDesc sandesha2TransportOutDesc = new Sandesha2TransportOutDesc();
		createSeqMsg.setTransportOut(sandesha2TransportOutDesc);

		// sending the message once through Sandesha2TransportSender.
		AxisEngine engine = new AxisEngine(createSeqMsg.getConfigurationContext());
		try {
			engine.resumeSend(createSeqMsg);
		} catch (AxisFault e) {
			throw new SandeshaException(e.getMessage());
		}

		if (log.isDebugEnabled())
			log.debug("Exit: ApplicationMsgProcessor::addCreateSequenceMessage");
	}

	private void processResponseMessage(RMMsgContext rmMsg, String internalSequenceId, long messageNumber,
			String storageKey, StorageManager storageManager) throws SandeshaException {
		if (log.isDebugEnabled())
			log.debug("Enter: ApplicationMsgProcessor::processResponseMessage, " + internalSequenceId);

		MessageContext msg = rmMsg.getMessageContext();
		SOAPFactory factory = SOAPAbstractFactory.getSOAPFactory(SandeshaUtil.getSOAPVersion(rmMsg.getSOAPEnvelope()));
		ConfigurationContext configurationContext = rmMsg.getMessageContext().getConfigurationContext();

		SequencePropertyBeanMgr sequencePropertyMgr = storageManager.getSequencePropertyBeanMgr();
		SenderBeanMgr retransmitterMgr = storageManager.getRetransmitterBeanMgr();

		SequencePropertyBean toBean = sequencePropertyMgr.retrieve(internalSequenceId,
				Sandesha2Constants.SequenceProperties.TO_EPR);
		SequencePropertyBean replyToBean = sequencePropertyMgr.retrieve(internalSequenceId,
				Sandesha2Constants.SequenceProperties.REPLY_TO_EPR);

		// again - looks weird in the client side - but consistent
		SequencePropertyBean outSequenceBean = sequencePropertyMgr.retrieve(internalSequenceId,
				Sandesha2Constants.SequenceProperties.OUT_SEQUENCE_ID);

		if (toBean == null) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.toEPRNotValid, null);
			log.debug(message);
			throw new SandeshaException(message);
		}

		EndpointReference toEPR = new EndpointReference(toBean.getValue());

		EndpointReference replyToEPR = null;
		if (replyToBean != null) {
			replyToEPR = new EndpointReference(replyToBean.getValue());
		}

		if (toEPR == null || toEPR.getAddress() == null || toEPR.getAddress() == "") {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.toEPRNotValid, null);
			log.debug(message);
			throw new SandeshaException(message);
		}

		String newToStr = null;
		if (msg.isServerSide()) {
			try {
				MessageContext requestMsg = msg.getOperationContext().getMessageContext(
						OperationContextFactory.MESSAGE_LABEL_IN_VALUE);
				if (requestMsg != null) {
					newToStr = requestMsg.getReplyTo().getAddress();
				}
			} catch (AxisFault e) {
				throw new SandeshaException(e.getMessage());
			}
		}

		if (newToStr != null)
			rmMsg.setTo(new EndpointReference(newToStr));
		else
			rmMsg.setTo(toEPR);

		if (replyToEPR != null)
			rmMsg.setReplyTo(replyToEPR);

		String rmVersion = SandeshaUtil.getRMVersion(internalSequenceId, storageManager);
		if (rmVersion == null)
			throw new SandeshaException(SandeshaMessageHelper.getMessage(
					SandeshaMessageKeys.specVersionPropertyNotAvailable, internalSequenceId));

		String rmNamespaceValue = SpecSpecificConstants.getRMNamespaceValue(rmVersion);

		Sequence sequence = new Sequence(rmNamespaceValue);
		MessageNumber msgNumber = new MessageNumber(rmNamespaceValue);
		msgNumber.setMessageNumber(messageNumber);
		sequence.setMessageNumber(msgNumber);

		boolean lastMessage = false;
		// setting last message
		if (msg.isServerSide()) {
			MessageContext requestMsg = null;

			try {
				requestMsg = msg.getOperationContext()
						.getMessageContext(OperationContextFactory.MESSAGE_LABEL_IN_VALUE);
			} catch (AxisFault e) {
				throw new SandeshaException(e.getMessage());
			}

			RMMsgContext reqRMMsgCtx = MsgInitializer.initializeMessage(requestMsg);
			Sequence requestSequence = (Sequence) reqRMMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.SEQUENCE);
			if (requestSequence == null) {
				String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.requestSeqIsNull);
				log.debug(message);
				throw new SandeshaException(message);
			}

			if (requestSequence.getLastMessage() != null) {
				lastMessage = true;
				sequence.setLastMessage(new LastMessage(rmNamespaceValue));

			}

		} else {
			// client side

			OperationContext operationContext = msg.getOperationContext();
			if (operationContext != null) {
				Object obj = msg.getProperty(SandeshaClientConstants.LAST_MESSAGE);
				if (obj != null && "true".equals(obj)) {
					lastMessage = true;

					SequencePropertyBean specVersionBean = sequencePropertyMgr.retrieve(internalSequenceId,
							Sandesha2Constants.SequenceProperties.RM_SPEC_VERSION);
					if (specVersionBean == null)
						throw new SandeshaException(SandeshaMessageHelper
								.getMessage(SandeshaMessageKeys.specVersionNotSet));

					String specVersion = specVersionBean.getValue();
					if (SpecSpecificConstants.isLastMessageIndicatorRequired(specVersion))
						sequence.setLastMessage(new LastMessage(rmNamespaceValue));
				}
			}
		}

		AckRequested ackRequested = null;

		boolean addAckRequested = false;
		// if (!lastMessage)
		// addAckRequested = true; //TODO decide the policy to add the
		// ackRequested tag

		// setting the Sequnece id.
		// Set send = true/false depending on the availability of the out
		// sequence id.
		String identifierStr = null;
		if (outSequenceBean == null || outSequenceBean.getValue() == null) {
			identifierStr = Sandesha2Constants.TEMP_SEQUENCE_ID;

		} else {
			identifierStr = (String) outSequenceBean.getValue();
		}

		Identifier id1 = new Identifier(rmNamespaceValue);
		id1.setIndentifer(identifierStr);
		sequence.setIdentifier(id1);
		rmMsg.setMessagePart(Sandesha2Constants.MessageParts.SEQUENCE, sequence);

		if (addAckRequested) {
			ackRequested = new AckRequested(rmNamespaceValue);
			Identifier id2 = new Identifier(rmNamespaceValue);
			id2.setIndentifer(identifierStr);
			ackRequested.setIdentifier(id2);
			rmMsg.setMessagePart(Sandesha2Constants.MessageParts.ACK_REQUEST, ackRequested);
		}

		try {
			rmMsg.addSOAPEnvelope();
		} catch (AxisFault e1) {
			throw new SandeshaException(e1.getMessage());
		}

		// Retransmitter bean entry for the application message
		SenderBean appMsgEntry = new SenderBean();

		appMsgEntry.setMessageContextRefKey(storageKey);

		appMsgEntry.setTimeToSend(System.currentTimeMillis());
		appMsgEntry.setMessageID(rmMsg.getMessageId());
		appMsgEntry.setMessageNumber(messageNumber);
		appMsgEntry.setMessageType(Sandesha2Constants.MessageTypes.APPLICATION);
		if (outSequenceBean == null || outSequenceBean.getValue() == null) {
			appMsgEntry.setSend(false);
		} else {
			appMsgEntry.setSend(true);
			// Send will be set to true at the sender.
			msg.setProperty(Sandesha2Constants.SET_SEND_TO_TRUE, Sandesha2Constants.VALUE_TRUE);
		}

		appMsgEntry.setInternalSequenceID(internalSequenceId);
		storageManager.storeMessageContext(storageKey, msg);
		retransmitterMgr.insert(appMsgEntry);
		msg.setProperty(Sandesha2Constants.QUALIFIED_FOR_SENDING, Sandesha2Constants.VALUE_FALSE);

		// changing the sender. This will set send to true.
		TransportSender sender = msg.getTransportOut().getSender();

		if (sender != null) {
			Sandesha2TransportOutDesc sandesha2TransportOutDesc = new Sandesha2TransportOutDesc();
			msg.setProperty(Sandesha2Constants.MESSAGE_STORE_KEY, storageKey);
			msg.setProperty(Sandesha2Constants.ORIGINAL_TRANSPORT_OUT_DESC, msg.getTransportOut());
			msg.setTransportOut(sandesha2TransportOutDesc);

		}

		// increasing the current handler index, so that the message will not be
		// going throught the SandeshaOutHandler again.
		msg.setCurrentHandlerIndex(msg.getCurrentHandlerIndex() + 1);

		// sending the message through, other handlers and the
		// Sandesha2TransportSender so that it get dumped to the storage.
		AxisEngine engine = new AxisEngine(msg.getConfigurationContext());
		try {
			engine.resumeSend(msg);
		} catch (AxisFault e) {
			throw new SandeshaException(e);
		}

		if (log.isDebugEnabled())
			log.debug("Exit: ApplicationMsgProcessor::processResponseMessage");
	}

	private long getPreviousMsgNo(ConfigurationContext context, String internalSequenceId, StorageManager storageManager)
			throws SandeshaException {
		if (log.isDebugEnabled())
			log.debug("Enter: ApplicationMsgProcessor::getPreviousMsgNo, " + internalSequenceId);

		SequencePropertyBeanMgr seqPropMgr = storageManager.getSequencePropertyBeanMgr();

		SequencePropertyBean nextMsgNoBean = seqPropMgr.retrieve(internalSequenceId,
				Sandesha2Constants.SequenceProperties.NEXT_MESSAGE_NUMBER);

		long nextMsgNo = -1;
		if (nextMsgNoBean != null) {
			Long nextMsgNoLng = new Long(nextMsgNoBean.getValue());
			nextMsgNo = nextMsgNoLng.longValue();
		}

		if (log.isDebugEnabled())
			log.debug("Exit: ApplicationMsgProcessor::getPreviousMsgNo, " + nextMsgNo);

		return nextMsgNo;
	}

	private void setNextMsgNo(ConfigurationContext context, String internalSequenceId, long msgNo,
			StorageManager storageManager) throws SandeshaException {

		if (log.isDebugEnabled())
			log.debug("Enter: ApplicationMsgProcessor::setNextMsgNo, " + internalSequenceId + ", " + msgNo);

		if (msgNo <= 0) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.msgNumberMustBeLargerThanZero, Long
					.toString(msgNo));
			throw new SandeshaException(message);
		}

		SequencePropertyBeanMgr seqPropMgr = storageManager.getSequencePropertyBeanMgr();

		SequencePropertyBean nextMsgNoBean = seqPropMgr.retrieve(internalSequenceId,
				Sandesha2Constants.SequenceProperties.NEXT_MESSAGE_NUMBER);

		boolean update = true;
		if (nextMsgNoBean == null) {
			update = false;
			nextMsgNoBean = new SequencePropertyBean();
			nextMsgNoBean.setSequenceID(internalSequenceId);
			nextMsgNoBean.setName(Sandesha2Constants.SequenceProperties.NEXT_MESSAGE_NUMBER);
		}

		nextMsgNoBean.setValue(new Long(msgNo).toString());
		if (update)
			seqPropMgr.update(nextMsgNoBean);
		else
			seqPropMgr.insert(nextMsgNoBean);

		if (log.isDebugEnabled())
			log.debug("Exit: ApplicationMsgProcessor::setNextMsgNo");
	}
}
