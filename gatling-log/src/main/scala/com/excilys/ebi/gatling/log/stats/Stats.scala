/**
 * Copyright 2011-2012 eBusiness Information, Groupe Excilys (www.excilys.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.excilys.ebi.gatling.log.stats

import com.excilys.ebi.gatling.log.Predef._
import com.twitter.scalding._
import com.excilys.ebi.gatling.log.stats.StatsHelper._
import com.excilys.ebi.gatling.log.util.FieldsNames._
import com.excilys.ebi.gatling.log.util.ResultBufferFinderAndParser
import com.excilys.ebi.gatling.log.util.ResultBufferType._
import grizzled.slf4j.Logging
import com.excilys.ebi.gatling.log.scalding.GatlingInputIteratorSource
import com.excilys.ebi.gatling.core.result.message.RecordType.ACTION
import com.excilys.ebi.gatling.core.action.EndAction.END_OF_SCENARIO
import com.excilys.ebi.gatling.core.action.StartAction.START_OF_SCENARIO

class Stats(min: Long, max: Long, step: Double, size: Long, inputIterator: Iterator[String]) extends Job(Args("")) with Logging {

	val header = (ACTION_TYPE, SCENARIO, ID, REQUEST, EXECUTION_START, EXECUTION_END, REQUEST_END, RESPONSE_START, STATUS)

	val demiStep = step / 2

	val range = (max - min) / SEC_MILLISEC_RATIO

	val input = GatlingInputIteratorSource(inputIterator, header, size)

	val bucketPipe = input.read
		.filter(ACTION_TYPE) {
		actionType: String => actionType == ACTION
	}
		.map((EXECUTION_START, EXECUTION_END, RESPONSE_START, REQUEST_END) ->(EXECUTION_START_BUCKET, EXECUTION_END_BUCKET, RESPONSE_START_BUCKET, REQUEST_END_BUCKET)) {
		t: (Long, Long, Long, Long) => {
			val (executionStart, executionEnd, responseStart, requestEnd) = t
			(bucket(executionStart, min, max, step, demiStep), bucket(executionEnd, min, max, step, demiStep), bucket(responseStart, min, max, step, demiStep), bucket(requestEnd, min, max, step, demiStep))
		}
	}
	/* SESSIONS STATS */
	val filteredSessionPipe = bucketPipe
		.filter(REQUEST) {
		req: String => req == START_OF_SCENARIO || req == END_OF_SCENARIO
	}

	/* BY SCENARIO */
	val pipeSessionDeltaPerBucketByScenario = filteredSessionPipe
		.map(REQUEST -> DELTA) {
		s: String =>
			s match {
				case START_OF_SCENARIO => 1
				case END_OF_SCENARIO => -1
			}
	}
		.groupBy((SCENARIO, EXECUTION_START_BUCKET)) {
		_.sum(DELTA)
	}.map(DELTA -> DELTA) {
		delta: Double => math.round(delta)
	}
		.write(output(ResultBufferFinderAndParser.SESSION_DELTA, BY_SCENARIO))

	pipeSessionDeltaPerBucketByScenario.groupBy(SCENARIO) {
		_.size(SIZE)
	}
		.project(SCENARIO)
		.write(output(ResultBufferFinderAndParser.SCENARIO, GLOBAL))

	/* ALL */
	val pipeSessionDeltaPerBucket = pipeSessionDeltaPerBucketByScenario
		.groupBy(EXECUTION_START_BUCKET) {
		_.sum(DELTA)
	}
		.map(DELTA -> DELTA) {
		delta: Double => math.round(delta)
	}
		.write(output(ResultBufferFinderAndParser.SESSION_DELTA, GLOBAL))


	/* REQUESTS STATS */
	val filteredRequestPipe = bucketPipe
		.filter(REQUEST) {
		req: String => req != START_OF_SCENARIO && req != END_OF_SCENARIO
	}

	val pipeResponseTimeAndLatency = filteredRequestPipe
		.map((EXECUTION_START, EXECUTION_END, REQUEST_END, RESPONSE_START) ->(RESPONSE_TIME, LATENCY, SQUARE_RESPONSE_TIME)) {
		t: (Long, Long, Long, Long) => {
			val (executionStart, executionEnd, requestEnd, responseStart) = t
			val (responseTime, latency) = (math.max(0L, executionEnd - executionStart), responseStart - requestEnd)
			(responseTime, latency, square(responseTime))
		}
	}

	/* BY REQUEST AND STATUS */
	val groupFields = (REQUEST, STATUS)

	val pipeResponseTimeDistributionByRequestAndStatus = pipeResponseTimeAndLatency.distributionSize(RESPONSE_TIME, groupFields)
		.write(output(ResultBufferFinderAndParser.RESPONSE_TIME_DISTRIBUTION, BY_STATUS_AND_REQUEST))

	val pipeStatsByRequestAndStatus = pipeResponseTimeAndLatency.groupBy(groupFields) {
		_.sizeAveStdev(RESPONSE_TIME ->(SIZE, MEAN, STD_DEV))
			.min(RESPONSE_TIME -> MIN)
			.max(RESPONSE_TIME -> MAX)
			.average(SQUARE_RESPONSE_TIME -> SQUARE_MEAN)
			.average(LATENCY -> MEAN_LATENCY)
	}
		.map(SIZE -> MEAN_REQUEST_PER_SEC) {
		count: Long => count / range
	}
		.write(output(ResultBufferFinderAndParser.GENERAL_STATS, BY_STATUS_AND_REQUEST))

	val pipeRequestPerSecByRequestAndStatus = filteredRequestPipe.distributionSize(EXECUTION_START_BUCKET, groupFields)
		.write(output(ResultBufferFinderAndParser.REQUESTS_PER_SEC, BY_STATUS_AND_REQUEST))

	val pipeTransactionPerSecByRequestAndStatus = filteredRequestPipe.distributionSize(EXECUTION_END_BUCKET, groupFields)
		.write(output(ResultBufferFinderAndParser.TRANSACTIONS_PER_SEC, BY_STATUS_AND_REQUEST))

	val pipeResponseTimePerSecByRequestAndStatus = pipeResponseTimeAndLatency.distributionMax(EXECUTION_START_BUCKET, groupFields, RESPONSE_TIME)
		.write(output(ResultBufferFinderAndParser.RESPONSE_TIME_PER_SEC, BY_STATUS_AND_REQUEST))

	val pipeLatencyPerSecByRequestAndStatus = pipeResponseTimeAndLatency.distributionMax(EXECUTION_START_BUCKET, groupFields, LATENCY)
		.write(output(ResultBufferFinderAndParser.LATENCY_PER_SEC, BY_STATUS_AND_REQUEST))

	/* BY REQUEST */
	val pipeRequestPerSecByRequest = pipeRequestPerSecByRequestAndStatus.groupByAndSum((REQUEST, EXECUTION_START_BUCKET), SIZE)
		.write(output(ResultBufferFinderAndParser.REQUESTS_PER_SEC, BY_REQUEST))

	val pipeResponseTimePerSecByRequest = pipeResponseTimePerSecByRequestAndStatus.groupByAndSum((REQUEST, EXECUTION_START_BUCKET), RESPONSE_TIME)
		.write(output(ResultBufferFinderAndParser.RESPONSE_TIME_PER_SEC, BY_REQUEST))

	pipeResponseTimeDistributionByRequestAndStatus.groupByAndSum((REQUEST, RESPONSE_TIME), SIZE)
		.write(output(ResultBufferFinderAndParser.RESPONSE_TIME_DISTRIBUTION, BY_REQUEST))

	pipeStatsByRequestAndStatus.mergeStats(range, REQUEST)
		.write(output(ResultBufferFinderAndParser.GENERAL_STATS, BY_REQUEST))

	/* GLOBAL BY STATUS */
	val pipeRequestPerSecByStatus = pipeRequestPerSecByRequestAndStatus.groupByAndSum((STATUS, EXECUTION_START_BUCKET), SIZE)
		.write(output(ResultBufferFinderAndParser.REQUESTS_PER_SEC, BY_STATUS))

	val pipeTransactionPerSecByStatus = pipeTransactionPerSecByRequestAndStatus.groupByAndSum((STATUS, EXECUTION_END_BUCKET), SIZE)
		.write(output(ResultBufferFinderAndParser.TRANSACTIONS_PER_SEC, BY_STATUS))

	val pipeResponseTimePerSecByStatus = pipeResponseTimePerSecByRequestAndStatus.groupByAndSum((STATUS, EXECUTION_START_BUCKET), RESPONSE_TIME)
		.write(output(ResultBufferFinderAndParser.RESPONSE_TIME_PER_SEC, BY_STATUS))

	val pipeResponseTimeDistributionByStatus = pipeResponseTimeDistributionByRequestAndStatus.groupByAndSum((STATUS, RESPONSE_TIME), SIZE)
		.write(output(ResultBufferFinderAndParser.RESPONSE_TIME_DISTRIBUTION, BY_STATUS))

	val pipeStatsByStatus = pipeStatsByRequestAndStatus.mergeStats(range, STATUS)
		.write(output(ResultBufferFinderAndParser.GENERAL_STATS, BY_STATUS))

	/* GLOBAL */
	val pipeRequestPerSec = pipeRequestPerSecByStatus.groupByAndSum(EXECUTION_START_BUCKET, SIZE)
		.write(output(ResultBufferFinderAndParser.REQUESTS_PER_SEC, GLOBAL))

	pipeTransactionPerSecByStatus.groupByAndSum(EXECUTION_END_BUCKET, SIZE)
		.write(output(ResultBufferFinderAndParser.TRANSACTIONS_PER_SEC, GLOBAL))

	val pipeResponseTimePerSec = pipeResponseTimePerSecByStatus.groupByAndSum(EXECUTION_START_BUCKET, RESPONSE_TIME)
		.write(output(ResultBufferFinderAndParser.RESPONSE_TIME_PER_SEC, GLOBAL))

	pipeResponseTimeDistributionByStatus.groupByAndSum(RESPONSE_TIME, SIZE)
		.write(output(ResultBufferFinderAndParser.RESPONSE_TIME_DISTRIBUTION, GLOBAL))

	pipeStatsByStatus.mergeStats(range)
		.write(output(ResultBufferFinderAndParser.GENERAL_STATS, GLOBAL))

	/* SCATTER PLOT */
	pipeResponseTimePerSecByRequestAndStatus.joinAndSort(EXECUTION_START_BUCKET -> EXECUTION_START_BUCKET, pipeRequestPerSec, (SIZE, RESPONSE_TIME), groupFields = groupFields)
		.write(output(ResultBufferFinderAndParser.REQUEST_AGAINST_RESPONSE_TIME, BY_STATUS_AND_REQUEST))

}
