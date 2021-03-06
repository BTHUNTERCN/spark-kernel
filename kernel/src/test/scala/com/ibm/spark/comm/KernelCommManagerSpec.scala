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

import com.ibm.spark.kernel.protocol.v5
import com.ibm.spark.kernel.protocol.v5._
import com.ibm.spark.kernel.protocol.v5.content.CommContent
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.{BeforeAndAfter, FunSpec, Matchers}

class KernelCommManagerSpec extends FunSpec with Matchers with BeforeAndAfter
  with MockitoSugar
{
  private val TestTargetName = "some target"

  private var mockActorLoader: ActorLoader = _
  private var mockKMBuilder: KMBuilder = _
  private var mockCommRegistrar: CommRegistrar = _
  private var kernelCommManager: KernelCommManager = _

  private var generatedCommWriter: CommWriter = _

  before {
    mockActorLoader = mock[ActorLoader]
    mockKMBuilder = mock[KMBuilder]
    mockCommRegistrar = mock[CommRegistrar]

    kernelCommManager = new KernelCommManager(
      mockActorLoader,
      mockKMBuilder,
      mockCommRegistrar
    ) {
      override protected def newCommWriter(commId: UUID): CommWriter = {
        val commWriter = super.newCommWriter(commId)

        generatedCommWriter = commWriter

        val spyCommWriter = spy(commWriter)
        doNothing().when(spyCommWriter)
          .sendCommKernelMessage(any[KernelMessageContent with CommContent])

        spyCommWriter
      }
    }
  }

  describe("KernelCommManager") {
    describe("#open") {
      it("should return a wrapped instance of KernelCommWriter") {
        kernelCommManager.open(TestTargetName, v5.Data())

        // Exposed hackishly for testing
        generatedCommWriter shouldBe a [KernelCommWriter]
      }
    }
  }
}
