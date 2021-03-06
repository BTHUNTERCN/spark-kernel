/*
 * Copyright 2014 IBM Corp.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.ibm.spark.comm

import com.ibm.spark.annotations.Experimental
import com.ibm.spark.kernel.protocol.v5.{ActorLoader, KMBuilder, UUID}

/**
 * Represents a CommManager that uses a KernelCommWriter for its underlying
 * open implementation.
 *
 * @param actorLoader The actor loader to use with the ClientCommWriter
 * @param kmBuilder The KMBuilder to use with the ClientCommWriter
 * @param commRegistrar The registrar to use for callback registration
 */
@Experimental
class KernelCommManager(
  private val actorLoader: ActorLoader,
  private val kmBuilder: KMBuilder,
  private val commRegistrar: CommRegistrar
) extends CommManager(commRegistrar)
{
  /**
   * Creates a new CommWriter instance given the Comm id.
   *
   * @param commId The Comm id to use with the Comm writer
   *
   * @return The new CommWriter instance
   */
  override protected def newCommWriter(commId: UUID): CommWriter =
    new KernelCommWriter(actorLoader, kmBuilder, commId)
}
