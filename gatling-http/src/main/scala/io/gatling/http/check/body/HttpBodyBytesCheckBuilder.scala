/**
 * Copyright 2011-2018 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.check.body

import io.gatling.commons.validation._
import io.gatling.core.check.DefaultFindCheckBuilder
import io.gatling.core.check.extractor._
import io.gatling.core.session._
import io.gatling.http.check.HttpCheck
import io.gatling.http.check.HttpCheckBuilders._
import io.gatling.http.response.Response

object HttpBodyBytesCheckBuilder {

  val BodyBytesExtractor = new Extractor[Array[Byte], Array[Byte]] with SingleArity {
    val name = "bodyBytes"
    def apply(prepared: Array[Byte]) = Some(prepared).success
  }.expressionSuccess

  val BodyBytes = new DefaultFindCheckBuilder[HttpCheck, Response, Array[Byte], Array[Byte]](
    BytesBodyExtender,
    ResponseBodyBytesPreparer,
    BodyBytesExtractor
  )
}