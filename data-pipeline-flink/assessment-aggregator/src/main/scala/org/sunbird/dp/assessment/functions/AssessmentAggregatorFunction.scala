package org.sunbird.dp.assessment.functions

import java.lang.reflect.Type
import java.math.BigDecimal
import java.sql.Timestamp
import java.text.DecimalFormat
import java.util

import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.{Row, UDTValue, UserType}
import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import com.google.gson.reflect.TypeToken
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory
import org.sunbird.dp.assessment.domain.Event
import org.sunbird.dp.assessment.task.AssessmentAggregatorConfig
import org.sunbird.dp.core.job.{BaseProcessFunction, Metrics}
import org.sunbird.dp.core.util.CassandraUtil

import scala.collection.JavaConverters._
import scala.collection.mutable

case class Question(id: String, maxscore: Double, params: util.List[LinkedTreeMap[String, Any]], title: String, `type`: String, desc: String)

case class QuestionData(resvalues: util.List[LinkedTreeMap[String, Any]], duration: Double, score: Double, item: Question)

case class AssessEvent(ets: Double, edata: QuestionData)


case class Aggregate(totalScore: Double, totalMaxScore: Double, grandTotal: String, questionsList: List[UDTValue])


class AssessmentAggregatorFunction(config: AssessmentAggregatorConfig,
                                   @transient var cassandraUtil: CassandraUtil = null
                                  )(implicit val mapTypeInfo: TypeInformation[Event])
  extends BaseProcessFunction[Event, Event](config) {

    val mapType: Type = new TypeToken[util.Map[String, AnyRef]]() {}.getType
    private[this] val logger = LoggerFactory.getLogger(classOf[AssessmentAggregatorFunction])
    var questionType: UserType = _
    private val df = new DecimalFormat("0.0#")

    override def metricsList() = List(config.dbUpdateCount, config.dbReadCount, config.failedEventCount, config.batchSuccessCount, config.skippedEventCount)


    override def open(parameters: Configuration): Unit = {
        super.open(parameters)
        cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
        questionType = cassandraUtil.getUDTType(config.dbKeyspace, config.dbudtType)
    }

    override def close(): Unit = {
        super.close()
    }

    def getListValues(values: util.List[LinkedTreeMap[String, Any]]): mutable.Buffer[util.Map[String, Any]] = {
        values.asScala.map { res =>
            res.asScala.map {
                case (key, value) => key -> (if (null != value && !value.isInstanceOf[String]) new Gson().toJson(value) else value)
            }.toMap.asJava
        }
    }

    /**
      * Method to write the assess event to cassandra table
      *
      * @param event   - Assess Batch Events
      * @param context - Process Context
      */
    override def processElement(event: Event,
                                context: ProcessFunction[Event, Event]#Context,
                                metrics: Metrics): Unit = {
        try {
            var totalScore = 0.0
            var totalMaxScore = 0.0
            val assessment = getAssessment(event)
            val assessEvents = event.assessEvents.asScala

            val sortAndFilteredEvents = assessEvents.map(event => {
                AssessEvent(event.get("ets").asInstanceOf[Double], new Gson().fromJson(new Gson().toJson(event.get("edata")),
                    classOf[QuestionData]))
            }).sortWith(_.ets > _.ets).groupBy(_.edata.item.id).map(_._2.head)

            val result = sortAndFilteredEvents.map(event => {
                totalScore = totalScore + event.edata.score
                totalMaxScore = totalMaxScore + event.edata.item.maxscore
                getQuestion(event.edata, event.ets.longValue())
            })

            val grandTotal = String.format("%s/%s", df.format(totalScore), df.format(totalMaxScore))

            if (null == assessment) {
                saveAssessment(event, Aggregate(totalScore, totalMaxScore, grandTotal, result.toList), new DateTime().getMillis)
                metrics.incCounter(config.dbUpdateCount)
                metrics.incCounter(config.batchSuccessCount)
            }
            else {
                metrics.incCounter(config.dbReadCount)
                if (event.assessmentEts > assessment.getTimestamp("last_attempted_on").getTime) {
                    saveAssessment(event, Aggregate(totalScore, totalMaxScore, grandTotal, result.toList),
                        assessment.getTimestamp("created_on").getTime)
                    metrics.incCounter(config.dbUpdateCount)
                    metrics.incCounter(config.batchSuccessCount)
                }
                else {
                    metrics.incCounter(config.skippedEventCount)
                }
            }
        } catch {
            case ex: Exception =>
                logger.info("Assessment Failed with exception :", ex)
                event.markFailed(ex.getMessage)
                context.output(config.failedEventsOutputTag, event)
                metrics.incCounter(config.failedEventCount)
        }
    }

    def getAssessment(event: Event): Row = {

        val query = QueryBuilder.select("last_attempted_on", "created_on")
          .from(config.dbKeyspace, config.dbTable).
          where(QueryBuilder.eq("attempt_id", event.attemptId))
          .and(QueryBuilder.eq("batch_id", event.batchId))
          .and(QueryBuilder.eq("user_id", event.userId))
          .and(QueryBuilder.eq("content_id", event.contentId))
          .and(QueryBuilder.eq("course_id", event.courseId)).toString

        cassandraUtil.findOne(query)

    }


    def saveAssessment(batchEvent: Event, aggregate: Aggregate, createdOn: Long): Unit = {
        val query = QueryBuilder.insertInto(config.dbKeyspace, config.dbTable)
          .value("course_id", batchEvent.courseId).value("batch_id", batchEvent.batchId).value("user_id", batchEvent.userId)
          .value("content_id", batchEvent.contentId).value("attempt_id", batchEvent.attemptId)
          .value("updated_on", new DateTime(DateTimeZone.UTC).getMillis).value("created_on", createdOn)
          .value("last_attempted_on", batchEvent.assessmentEts).value("total_score", aggregate.totalScore)
          .value("total_max_score", aggregate.totalMaxScore)
          .value("question", aggregate.questionsList.asJava).value("grand_total", aggregate.grandTotal).toString

        cassandraUtil.upsert(query)
        logger.info("Successfully Aggregated the batch event - batchid: "
          + batchEvent.batchId + " ,userid: " + batchEvent.userId + " ,couserid: "
          + batchEvent.courseId + " ,contentid: " + batchEvent.contentId, "attempid" + batchEvent.attemptId)
    }

    def getQuestion(questionData: QuestionData, assessTs: Long): UDTValue =

        questionType.newValue().setString("id", questionData.item.id).setDouble("max_score", questionData.item.maxscore)
          .setDouble("score", questionData.score)
          .setString("type", questionData.item.`type`)
          .setString("title", questionData.item.title)
          .setList("resvalues", getListValues(questionData.resvalues).asJava).setList("params", getListValues(questionData.item.params).asJava)
          .setString("description", questionData.item.desc)
          .setDecimal("duration", BigDecimal.valueOf(questionData.duration)).setTimestamp("assess_ts", new Timestamp(assessTs))

}

