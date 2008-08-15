/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.transport.testkit.tests.async;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.axiom.om.OMElement;
import org.apache.synapse.transport.testkit.TestEnvironment;
import org.apache.synapse.transport.testkit.client.AsyncTestClient;
import org.apache.synapse.transport.testkit.listener.AsyncChannel;
import org.apache.synapse.transport.testkit.listener.AsyncMessageTestCase;
import org.apache.synapse.transport.testkit.listener.ContentTypeMode;
import org.apache.synapse.transport.testkit.message.MessageData;
import org.apache.synapse.transport.testkit.message.RESTMessage;
import org.apache.synapse.transport.testkit.message.RESTMessage.Parameter;
import org.apache.synapse.transport.testkit.name.DisplayName;
import org.apache.synapse.transport.testkit.server.AsyncEndpointFactory;

@DisplayName("REST")
public class RESTTestCase<E extends TestEnvironment,C extends AsyncChannel<? super E>> extends AsyncMessageTestCase<E,C,RESTMessage,MessageData> {
    private final RESTMessage message;
    
    public RESTTestCase(E env, C channel, AsyncTestClient<? super E,? super C,RESTMessage> client, AsyncEndpointFactory<? super E,? super C,MessageData> endpointFactory, RESTMessage message) {
        super(env, channel, client, endpointFactory, ContentTypeMode.TRANSPORT, null, null);
        this.message = message;
    }
    
    @Override
    protected RESTMessage prepareMessage() throws Exception {
        return message;
    }

    @Override
    protected void checkMessageData(RESTMessage message, MessageData messageData) throws Exception {
        OMElement content = messageData.getEnvelope().getBody().getFirstElement();
        Set<Parameter> expected = new HashSet<Parameter>(Arrays.asList(message.getParameters()));
        for (Iterator<?> it = content.getChildElements(); it.hasNext(); ) {
            OMElement child = (OMElement)it.next();
            assertTrue(expected.remove(new Parameter(child.getLocalName(), child.getText())));
        }
        assertTrue(expected.isEmpty());
    }
}
