/*
 * Copyright 2011-2021 GatlingCorp (https://gatling.io)
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

package io.gatling.http.action.sse

import scala.concurrent.duration.FiniteDuration

import io.gatling.core.action.Action
import io.gatling.core.session._
import io.gatling.core.structure.ScenarioContext
import io.gatling.http.action.HttpActionBuilder
import io.gatling.http.check.sse.{ SseMessageCheck, SseMessageCheckSequence }

import com.softwaremill.quicklens._

final case class SseSetCheckBuilder(
    requestName: Expression[String],
    sseName: Expression[String],
    checkSequences: List[SseMessageCheckSequenceBuilder]
) extends HttpActionBuilder {

  def await(timeout: FiniteDuration)(checks: SseMessageCheck*): SseSetCheckBuilder =
    await(timeout.expressionSuccess)(checks: _*)

  @SuppressWarnings(Array("org.wartremover.warts.ListAppend"))
  def await(timeout: Expression[FiniteDuration])(checks: SseMessageCheck*): SseSetCheckBuilder = {
    require(!checks.contains(null), "Checks can't contain null elements. Forward reference issue?")
    this.modify(_.checkSequences)(_ :+ SseMessageCheckSequenceBuilder(timeout, checks.toList))
  }

  override def build(ctx: ScenarioContext, next: Action): Action =
    new SseSetCheck(requestName, checkSequences, sseName, ctx.coreComponents.statsEngine, ctx.coreComponents.clock, next)
}

final case class SseCloseBuilder(requestName: Expression[String], sseName: Expression[String]) extends HttpActionBuilder {

  override def build(ctx: ScenarioContext, next: Action): Action =
    new SseClose(requestName, sseName, ctx.coreComponents.statsEngine, ctx.coreComponents.clock, next)
}
