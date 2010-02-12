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

package org.apache.synapse.commons.evaluators;

/**
 * This encapsulates two or more boolean conditions. This acts as the "or"
 * boolean operator.
 *
 * Syntax:
 * <or>
 *     two or more evaluators
 * </or>
 */
public class Or implements Evaluator {
    private Evaluator[] evaluators;

    public boolean evaluate(EvaluatorContext context) throws EvaluatorException {
        for (Evaluator e : evaluators) {
            if (e.evaluate(context)) {
                return true;
            }
        }
        return false;
    }

    public String getName() {
        return "or";
    }

    public void setEvaluators(Evaluator[] evaluators) {
        this.evaluators = evaluators;
    }
}
