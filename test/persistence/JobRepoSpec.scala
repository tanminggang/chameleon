package persistence

import akka.actor.ActorSystem
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import fixtures.DatabaseFixture
import model._
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll, MustMatchers, OptionValues}
import org.scalatestplus.mockito.MockitoSugar
import play.api.db.Database
import play.api.db.slick.DatabaseConfigProvider
import slick.basic.{BasicProfile, DatabaseConfig}
import slick.jdbc.JdbcProfile

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps

class JobRepoSpec extends AsyncFlatSpec with MockitoSugar with MustMatchers
  with ForAllTestContainer with BeforeAndAfterAll with OptionValues {
  info("Specification of features of the persistence mechanism")

  private implicit val system: ActorSystem = ActorSystem.create("test-actor-system")
  private implicit val ec: ExecutionContext = system.dispatcher
  override val container = PostgreSQLContainer()
  private implicit val dbConfigProvider: DatabaseConfigProvider = new DatabaseConfigProvider {
    override def get[P <: BasicProfile]: DatabaseConfig[P] =
      DatabaseConfig.forConfig[JdbcProfile]("slick.dbs.default", configureDb()).asInstanceOf[DatabaseConfig[P]]
  }

  val jobSpec = JobSpecification(1, Array(
    Batch(Paint(1, Finish.Matte)),
    Batch(Paint(1, Finish.Glossy))
  ))

  "When a job is created and stored, it" should "be retrievable by user's email" in {
    val subject = new JobRepository()
    val userEmail = EmailAddress("test1@mail.com").value
    val process = for {
      _ <- subject.create(Job(userEmail,jobSpec))
      r <- subject.findByUserEmail(userEmail)
    } yield r

    process map {
      case job +: _ =>
        job.userEmail must be(userEmail)
      case unexpected =>
        fail(unexpected.toString())
    }
  }

  "When an attempt is made to update a job concurrently, one update" should
    "fail with a useful result" in {
    val subject = new JobRepository()
    val userEmail = EmailAddress("test2@mail.com").value
    val process = for {
      job <- subject.create(Job(userEmail,jobSpec))
      update1 <- subject.update(job)
      update2 <- subject.update(job)
    } yield Seq(update1,update2)

    val t = intercept[RuntimeException] {
      Await.result(process, 10 seconds)
    }
    t.getMessage must include("Concurrent update failure")
  }

  "When a job is updated, the changes" should "be persisted in the database" in {
    val subject = new JobRepository()
    val solution = MixSolution(Seq(Finish.Glossy,Finish.Matte))
    val userEmail = EmailAddress("test2@mail.com").value
    val process = for {
      job <- subject.create(Job(userEmail,jobSpec))
      _ <- subject.update(job.withSolution(solution))
      r <- subject.findById(job.jobId)
    } yield r

    process map {
      case Some(job) =>
        job.userEmail must be(userEmail)
        job.status must be(JobStatus.Completed)
        job.result.isDefined must be(true)
      case None =>
        fail
    }
  }

  "all existing jobs" should "be returned" in {
    val subject = new JobRepository()
    val inserts = Future.sequence((1 to 10) map { i =>
      subject.create(Job(EmailAddress(s"test$i@mail.com").value, jobSpec))
    })
    val process = for {
      _ <- inserts
      query <- subject.findAll
    } yield query
    process map {
      case jobs if jobs.nonEmpty =>
        jobs.size >= 10 must be(true)
      case _ =>
        fail
    }
  }

  private var database: Option[Database] = None
  override def beforeAll(): Unit = {
    container.start()
    val dbProfile = dbConfigProvider.get[JdbcProfile].config
    val dbConfig = ConfigFactory.parseMap(Map(
      "db.properties.url" -> container.jdbcUrl,
      "db.username" -> container.username,
      "db.password" -> container.password,
      "db.properties.driver" -> "slick.driver.PostgresDriver$",
      "db.dataSourceClass" -> "org.postgresql.Driver").asJava)
    val db = DatabaseFixture(dbConfig,"default")
    database = Some(db)
    db.evolve()
  }

  override def afterAll(): Unit = {
    database foreach (_.shutdown())
    container.stop
    system.terminate()
  }

  private def configureDb(): Config = {
    ConfigFactory.parseMap(Map(
      "slick.dbs.default.driver" -> "slick.driver.PostgresDriver$",
      "slick.dbs.default.db.url" -> container.jdbcUrl,
      "slick.dbs.default.db.user" -> container.username,
      "slick.dbs.default.db.password" -> container.password,
      "slick.dbs.default.db.driver" -> "org.postgresql.Driver",
      "slick.dbs.default.db.numThreads" -> "1",
      "slick.dbs.default.db.maxConnections" -> "10").asJava)
  }

}
