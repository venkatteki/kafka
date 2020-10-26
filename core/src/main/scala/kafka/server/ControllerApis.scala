/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import kafka.network.RequestChannel
import kafka.server.QuotaFactory.QuotaManagers
import kafka.utils.Logging
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.acl.AclOperation.CLUSTER_ACTION
import org.apache.kafka.common.errors.ApiException
import org.apache.kafka.common.internals.FatalExitError
import org.apache.kafka.common.message.ApiVersionsResponseData
import org.apache.kafka.common.message.ApiVersionsResponseData.{ApiVersionsResponseKey, FinalizedFeatureKey, SupportedFeatureKey}
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.requests.{AlterIsrRequest, ApiVersionsRequest, ApiVersionsResponse}
import org.apache.kafka.common.resource.Resource.CLUSTER_NAME
import org.apache.kafka.common.resource.ResourceType.CLUSTER
import org.apache.kafka.common.utils.Time
import org.apache.kafka.controller.{Controller, LeaderAndIsr}
import org.apache.kafka.metadata.FeatureManager
import org.apache.kafka.server.authorizer.Authorizer

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * Request handler for Controller APIs
 */
class ControllerApis(val requestChannel: RequestChannel,
                     val authorizer: Option[Authorizer],
                     val quotas: QuotaManagers,
                     val time: Time,
                     val featureManager: FeatureManager,
                     val controller: Controller) extends ApiRequestHandler with Logging {

  val apisUtil = new ApisUtils(requestChannel, authorizer, quotas, time)

  val supportedApiKeys = Set(
    ApiKeys.API_VERSIONS,
    ApiKeys.ALTER_ISR
  )

  override def handle(request: RequestChannel.Request): Unit = {
    try {
      request.header.apiKey match {
        case ApiKeys.API_VERSIONS => handleApiVersionsRequest(request)
        case ApiKeys.ALTER_ISR => handleAlterIsrRequest(request)
          // TODO other APIs
        case _ => throw new ApiException(s"Unsupported ApiKey ${request.context.header.apiKey()}")
      }
    } catch {
      case e: FatalExitError => throw e
      case e: Throwable => apisUtil.handleError(request, e)
    } finally {

    }
  }

  def handleApiVersionsRequest(request: RequestChannel.Request): Unit = {
    // Note that broker returns its full list of supported ApiKeys and versions regardless of current
    // authentication state (e.g., before SASL authentication on an SASL listener, do note that no
    // Kafka protocol requests may take place on an SSL listener before the SSL handshake is finished).
    // If this is considered to leak information about the broker version a workaround is to use SSL
    // with client authentication which is performed at an earlier stage of the connection where the
    // ApiVersionRequest is not available.
    def createResponseCallback(requestThrottleMs: Int): ApiVersionsResponse = {
      val apiVersionRequest = request.body[ApiVersionsRequest]
      if (apiVersionRequest.hasUnsupportedRequestVersion)
        apiVersionRequest.getErrorResponse(requestThrottleMs, Errors.UNSUPPORTED_VERSION.exception)
      else if (!apiVersionRequest.isValid)
        apiVersionRequest.getErrorResponse(requestThrottleMs, Errors.INVALID_REQUEST.exception)
      else {
        val finalized = featureManager.finalizedFeatures()
        val data = new ApiVersionsResponseData().
          setErrorCode(0.toShort).
          setThrottleTimeMs(requestThrottleMs).
          setFinalizedFeaturesEpoch(finalized.epoch())
        featureManager.supportedFeatures().asScala.foreach {
          case (k, v) => data.supportedFeatures().add(new SupportedFeatureKey().
            setName(k).setMaxVersion(v.max()).setMinVersion(v.min()))
        }
        finalized.finalizedFeatures().asScala.foreach {
          case (k, v) => data.finalizedFeatures().add(new FinalizedFeatureKey().
            setName(k).setMaxVersionLevel(v.max()).setMinVersionLevel(v.min()))
        }
        ApiKeys.enabledApis().asScala.foreach {
          case key => if (supportedApiKeys.contains(key)) {
            data.apiKeys().add(new ApiVersionsResponseKey().
              setApiKey(key.id).
              setMaxVersion(key.latestVersion()).
              setMinVersion(key.oldestVersion()))
          }
        }
        new ApiVersionsResponse(data)
      }
    }
    apisUtil.sendResponseMaybeThrottle(request, createResponseCallback)
  }

  def handleAlterIsrRequest(request: RequestChannel.Request): Unit = {
    val alterIsrRequest = request.body[AlterIsrRequest]
    if (!apisUtil.authorize(request.context, CLUSTER_ACTION, CLUSTER, CLUSTER_NAME)) {
      val isrsToAlter = mutable.Map[TopicPartition, LeaderAndIsr]()
      alterIsrRequest.data.topics.forEach { topicReq =>
        topicReq.partitions.forEach { partitionReq =>
          val tp = new TopicPartition(topicReq.name, partitionReq.partitionIndex)
          val newIsr = partitionReq.newIsr()
          isrsToAlter.put(tp, new LeaderAndIsr(
            alterIsrRequest.data.brokerId,
            partitionReq.leaderEpoch,
            newIsr,
            partitionReq.currentIsrVersion))
        }
      }

      controller.alterIsr(
        alterIsrRequest.data().brokerId(),
        alterIsrRequest.data().brokerEpoch(),
        isrsToAlter.asJava)
    }
  }
}
