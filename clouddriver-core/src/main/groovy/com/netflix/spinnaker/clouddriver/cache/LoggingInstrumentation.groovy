/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.cache

import com.netflix.spinnaker.cats.agent.Agent
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LoggingInstrumentation implements ExecutionInstrumentation {
  private final Logger logger = LoggerFactory.getLogger(LoggingInstrumentation)

  @Override
  void executionStarted(Agent agent) {
    logger.debug("${agent.providerName}:${agent.agentType} starting")
  }

  @Override
  void executionCompleted(Agent agent, long durationMs) {
    logger.debug("${agent.providerName}:${agent.agentType} completed in ${durationMs / 1000}s")
  }

  @Override
  void executionFailed(Agent agent, Throwable cause, long durationMs) {
    logger.warn("${agent.providerName}:${agent.agentType} completed with one or more failures in  ${durationMs / 1000}s", cause)
  }
}
