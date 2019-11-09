package persistence

import java.time.LocalDateTime

import akka.actor.ActorSystem
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import fixtures.DatabaseFixture
import model.{Job, JobStatus}
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll, MustMatchers}
import org.scalatestplus.mockito.MockitoSugar
import play.api.db.Database
import play.api.db.slick.DatabaseConfigProvider
import slick.basic.{BasicProfile, DatabaseConfig}
import slick.jdbc.JdbcProfile

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class JobRepoSpec extends AsyncFlatSpec with MockitoSugar with MustMatchers
  with ForAllTestContainer with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem.create("test-actor-system")

  override val container = PostgreSQLContainer()
  private implicit val dbConfigProvider: DatabaseConfigProvider = new DatabaseConfigProvider {
    override def get[P <: BasicProfile]: DatabaseConfig[P] =
      DatabaseConfig.forConfig[JdbcProfile]("slick.dbs.default", configureDb()).asInstanceOf[DatabaseConfig[P]]
  }

  "When a job is created and stored, it" should
    "be retrievable by user's email" in {
    val subject = new JobRepository()

    subject.create(Job(0, "test1@mail.com", "", LocalDateTime.now(), JobStatus.Created))

    val result = Await.result(subject.findByUserEmail("test1@mail.com"), 2 seconds)
    result match {
      case job +: _ =>
        job.userEmail must be("test1@mail.com")
      case unexpected =>
        fail(unexpected.toString())
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
      "slick.dbs.default.db.url" -> container.jdbcUrl,
      "slick.dbs.default.db.user" -> container.username,
      "slick.dbs.default.db.password" -> container.password,
      "slick.dbs.default.driver" -> "slick.driver.PostgresDriver$",
      "slick.dbs.default.db.driver" -> "org.postgresql.Driver",
      "slick.dbs.default.db.numThreads" -> "1",
      "slick.dbs.default.db.maxConnections" -> "10").asJava)
  }

}