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

package com.ibm.spark.kernel.api

import java.io.{PrintStream, InputStream, OutputStream}

/**
 * Interface for the kernel API. This does not include exposed variables.
 */
trait KernelLike {
  /**
   * Executes a block of code represented as a string and returns the result.
   *
   * @param code The code as an option to execute
   *
   * @return A tuple containing the result (true/false) and the output as a
   *         string
   */
  def eval(code: Option[String]): (Boolean, String)

  /**
   * Returns a print stream to be used for communication back to clients
   * via standard out.
   *
   * @return The print stream instance or an error if the stream info is
   *         not found
   */
  def out(implicit streamInfo: StreamInfo): PrintStream

  /**
   * Returns a print stream to be used for communication back to clients
   * via standard error.
   *
   * @return The print stream instance or an error if the stream info is
   *         not found
   */
  def err(implicit streamInfo: StreamInfo): PrintStream

  /**
   * Returns an input stream to be used to receive information from the client.
   *
   * @return The input stream instance or an error if the stream info is
   *         not found
   */
  def in(implicit streamInfo: StreamInfo): InputStream = ??? // Not implemented
}
