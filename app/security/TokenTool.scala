package security

import java.time.{LocalDateTime, ZoneOffset}

import scala.io.AnsiColor._
import scala.util.Try

object TokenTool extends App {
  val prompts = Seq(
    ("secret-key", "(play.http.secret.key of deployed service)"),
    ("email", "(e.g., user@domain.tld)"),
    ("role", "(Admin or Customer)"),
    ("expires", "(ISO: e.g., 2020-12-12T12:33:34)")
  )
  println(s"$BOLD*** Generate a JWT token for API Authorization ***$RESET")
  println(" (make no mistakes - there are no second chances)")

  println(s"$prompts")
  val input = prompts map { prompt =>
    print(s"${prompt._1} $CYAN${prompt._2}:$RESET ")
    val input = scala.io.StdIn.readLine()
    if (input.isEmpty || input.toLowerCase == "q") {
      System.exit(0)
    }
    (prompt -> input)
  }
  val token = generate(input.map(entry => entry._1._1 -> entry._2).toMap)
  println(s"JWT:$YELLOW${token.getOrElse(s"${RED}failed to generate jwt token from $input}")}$RESET")

  private def generate(profile: Map[String, String]) = {
    val secretKey = profile("secret-key").trim
    val email = profile("email").trim.toLowerCase
    val role = profile("role").trim
    val expires = profile("expires").trim
    val expiry = Try(LocalDateTime.parse(expires).toEpochSecond(ZoneOffset.UTC)).toOption.getOrElse("")
    val payload = s"""{"email":"$email","role":"$role","exp":$expiry}"""
    new JwtUtility(secretKey, () => LocalDateTime.now).createToken(payload)
  }


}