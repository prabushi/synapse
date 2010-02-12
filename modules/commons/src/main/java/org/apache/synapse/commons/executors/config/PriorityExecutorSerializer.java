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

package org.apache.synapse.commons.executors.config;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.synapse.commons.executors.*;

import javax.xml.namespace.QName;
import java.util.List;

public class PriorityExecutorSerializer {

    public static OMElement serialize(OMElement parent,
                                      PriorityExecutor executor, String namespace) {        
        QName executorQName = createQname(namespace, ExecutorConstants.PRIORITY_EXECUTOR);
        QName queuesQName = createQname(namespace, ExecutorConstants.QUEUES);
        QName queueQName = createQname(namespace, ExecutorConstants.QUEUE);
        QName threadsQName = createQname(namespace, ExecutorConstants.THREADS);


        OMFactory fac = OMAbstractFactory.getOMFactory();
        OMElement executorElement = fac.createOMElement(executorQName);
        OMNamespace nullNS = fac.createOMNamespace("", "");

        if (executor.getName() != null) {
            executorElement.addAttribute(fac.createOMAttribute(ExecutorConstants.NAME,
                    nullNS, executor.getName()));
        }

        if (executor.getBeforeExecuteHandler() != null) {
            executorElement.addAttribute(fac.createOMAttribute(
                    ExecutorConstants.BEFORE_EXECUTE_HANDLER, nullNS,
                    executor.getBeforeExecuteHandler().getClass().getName()));
        }

        // create the queues configuration
        MultiPriorityBlockingQueue queue = executor.getQueue();
        NextQueueAlgorithm algo = queue.getNextQueue();
        OMElement queuesEle = fac.createOMElement(queuesQName);

        if (!(algo instanceof PRRNextQueueAlgorithm)) {
            queuesEle.addAttribute(fac.createOMAttribute(ExecutorConstants.NEXT_QUEUE, nullNS,
                    algo.getClass().getName()));            
        }

        if (!queue.isFixedSizeQueues()) {
            queuesEle.addAttribute(fac.createOMAttribute(ExecutorConstants.IS_FIXED_SIZE,
                    nullNS, Boolean.toString(false)));
        }

        List<InternalQueue> intQueues = queue.getQueues();
        for (InternalQueue intQueue : intQueues) {
            OMElement queueEle = fac.createOMElement(queueQName);

            if (queue.isFixedSizeQueues()) {
                queueEle.addAttribute(fac.createOMAttribute(ExecutorConstants.SIZE, nullNS,
                        Integer.toString(intQueue.getCapacity())));
            }

            queueEle.addAttribute(fac.createOMAttribute(ExecutorConstants.PRIORITY, nullNS,
                    Integer.toString(intQueue.getPriority())));

            queuesEle.addChild(queueEle);
        }
        executorElement.addChild(queuesEle);

        // create the Threads configuration
        OMElement threadsEle = fac.createOMElement(threadsQName);
        threadsEle.addAttribute(fac.createOMAttribute(
                ExecutorConstants.MAX, nullNS, Integer.toString(executor.getMax())));
        threadsEle.addAttribute(fac.createOMAttribute(
                ExecutorConstants.CORE, nullNS, Integer.toString(executor.getCore())));
        threadsEle.addAttribute(fac.createOMAttribute(
                ExecutorConstants.KEEP_ALIVE, nullNS, Integer.toString(executor.getKeepAlive())));

        executorElement.addChild(threadsEle);

        if (parent != null) {
            parent.addChild(executorElement);
        }

        return executorElement;
    }

    private static QName createQname(String namespace, String name) {
        if (namespace == null) {
            return new QName(name);
        }
        return new QName(namespace, name, "syn");
    }
}

