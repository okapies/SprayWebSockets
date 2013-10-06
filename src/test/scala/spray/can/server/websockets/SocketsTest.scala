package spray.can.server.websockets

import model._
import model.Frame.Successful
import model.OpCode.{ConnectionClose, Text}
import org.scalatest.FreeSpec
import akka.actor._
import concurrent.duration._
import spray.io._
import scala.concurrent.{Promise, Await}
import akka.util.{ByteString, Timeout}
import spray.http.HttpRequest
import akka.testkit.TestActorRef
import org.scalatest.concurrent.Eventually

import javax.net.ssl.{SSLEngine, TrustManagerFactory, KeyManagerFactory, SSLContext}
import java.security.{SecureRandom, KeyStore}

import scala.Some

import org.scalatest.time.{Seconds, Span}
import akka.io._
import spray.can.Http
import java.net.InetSocketAddress
import akka.io.Tcp.{Register, Write, Connected}
import spray.io.Pipeline.Tell
import spray.can.server.websockets.Sockets.RoundTripTime
import akka.io.TcpPipelineHandler.WithinActorContext
import spray.io.Pipeline.Tell
import scala.Some
import akka.io.SslTlsSupport
import spray.can.server.websockets.Sockets.RoundTripTime
import akka.io.IO
import spray.http.HttpRequest
import spray.can.server.websockets.model.Frame.Successful
import akka.io.Tcp.Connected
import akka.io.Tcp.Register
import spray.can.server.ServerSettings

class SocketsTest extends FreeSpec with Eventually{
  implicit def string2bytestring(s: String) = ByteString(s)
  implicit val system = ActorSystem()
  implicit val timeout = akka.util.Timeout(5 seconds)

  implicit val sslContext = Util.createSslContext("/ssl-test-keystore.jks", "")

  val websocketClientHandshake =
    "GET /mychat HTTP/1.1\r\n" +
    "Host: server.example.com\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n\r\n"
  import scala.concurrent.duration.Duration
  /**
   * HttpHandler which always accepts websocket upgrade requests
   */
  class ServerActor(maxMessageSize: Int = Int.MaxValue, autoPingInterval: Duration = Duration.Inf, echoActor: => Actor = new EchoActor()) extends Actor{
    def receive = {
      case req: HttpRequest =>
        sender ! Sockets.acceptAllFunction(req)
        sender ! Sockets.Upgrade(TestActorRef(echoActor), autoPingInterval, () => Array(), maxMessageSize)

      case x: Connected =>
        sender ! Register(self)
    }
  }

  /**
   * Websocket frameHandler which echoes any frames sent to it, but
   * capitalizes them and keeps count so you know it's alive
   */
  class EchoActor extends Actor{
    var count = 0
    def receive = {
      case f @ Frame(fin, rsv, Text, maskingKey, data) =>
        count = count + 1
        sender ! Frame(fin, rsv, Text, None, (f.stringData.toUpperCase + count))
      case x =>
    }
  }

  /**
   * Sets up a SocketServer and a IOClientConnection talking to it.
   */
  def setupConnection(port: Int = 10000 + util.Random.nextInt(1000),
                      serverActor: => Actor,
                      ssl: Boolean) = {
    val reqHandler = system.actorOf(Props(serverActor))

    IO(Sockets) ! Http.Bind(
      reqHandler,
      "localhost",
      port,
      settings=Some(ServerSettings(system).copy(sslEncryption = ssl))
    )

    val client = TestActorRef(new Util.TestClientActor(ssl = ssl))
    IO(Tcp).!(Tcp.Connect(new InetSocketAddress("localhost", port)))(client)
    client await Tcp.Write(ByteString(websocketClientHandshake))
    client
  }

  /**
   * Registers the given test twice, with and without SSL.
   */
  def doTwice(serverActor: => Actor = new ServerActor(Int.MaxValue),
              port: Int = 1000 + util.Random.nextInt(10000))
             (test: TestActorRef[Util.TestClientActor]   => Unit) = {
    "basic" in test(setupConnection(port, serverActor, ssl=false))
    "ssl" in test(setupConnection(port + 1, serverActor, ssl=true))
  }

  "Echo Server Tests" - {
    "hello world with echo server" - doTwice(){ connection =>
      def frame = Frame(true, (false, false, false), OpCode.Text, Some(12345123), "i am cow")
      println("First Message")
      val r3 = connection await frame
      assert(r3.stringData === "I AM COW1")
      println("Second Message")
      val r4 = connection await frame
      assert(r4.stringData === "I AM COW2")
    }

    "Testing ability to receive fragmented message" - doTwice(){ connection =>

      val result1 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ")
        connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ")
      }
      assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")

      val result2 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "yoghurt curds cream cheese and butter ")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "comes from liquids from my udder ")
        connection await Frame(opcode = OpCode.Text, maskingKey = Some(-23), data = "i am cow, i am cow, hear me moooo ")
      }
      assert(result2.stringData === "YOGHURT CURDS CREAM CHEESE AND BUTTER COMES FROM LIQUIDS FROM MY UDDER I AM COW, I AM COW, HEAR ME MOOOO 2")
      
    }
    "Ping/Pong" - {
      "simple responses" - doTwice(){ connection =>
        
        val res1 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow")
        assert(res1.stringData === "i am cow")
        val res2 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow")
        assert(res2.stringData === "i am cow")
      
      }
      "responding in middle of fragmented message" - doTwice(){connection =>
        val result1 = {
          connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ")
          connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ")

          val res1 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow")
          assert(res1.stringData === "i am cow")

          connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ")

          val res2 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow")
          assert(res2.stringData === "i am cow")

          connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ")
        }
        assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")
      }
    }

    "Closing Tests" - {
      "Clean Close" - doTwice(){ connection =>
        val res1 = connection await Frame(opcode = OpCode.ConnectionClose, maskingKey = Some(0))
        assert(res1.opcode === OpCode.ConnectionClose)

      }
      "The server MUST close the connection upon receiving a frame that is not masked" - doTwice(){ connection =>
        val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("lol"))
        assert(res1.opcode === OpCode.ConnectionClose)
        assert(res1.data.asByteBuffer.getShort === CloseCode.ProtocolError.statusCode)

      }

      "The server must close the connection if the frame is too large" - {
        "single large frame" - doTwice(new ServerActor(maxMessageSize = 1024)){ connection =>
        // just below the limit works
          val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("l" * 1024), maskingKey = Some(0))
          assert(res1.opcode === OpCode.Text)
          assert(res1.stringData === ("L" * 1024 + "1"))

          // just above the limit
          val res2 = connection await Frame(opcode = OpCode.Text, data = ByteString("l" * 1025), maskingKey = Some(0))
          assert(res2.data.asByteBuffer.getShort === CloseCode.MessageTooBig.statusCode)

        }
        "fragmented large frame" - doTwice(new ServerActor(maxMessageSize = 1024)){ connection =>
          // just below the limit works
          connection send Frame(FIN = false, opcode = OpCode.Text, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          val res1 = connection await Frame(opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          assert(res1.opcode === OpCode.Text)
          assert(res1.stringData === ("L" * 1024 + "1"))

          // just above the limit
          connection send Frame(FIN = false, opcode = OpCode.Text, data = ByteString("l" * 257), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          val res2 = connection await Frame(opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          assert(res2.data.asByteBuffer.getShort === CloseCode.MessageTooBig.statusCode)

        }
      }
    }
    "pinging" - {

      "ping pong" - doTwice(){connection =>
        val res1 = connection await Frame(opcode = OpCode.Ping, data = ByteString("hello ping"), maskingKey = Some(12345))
        assert(res1.stringData === "hello ping")
        assert(res1.opcode === OpCode.Pong)

        val res2 = connection await Frame(opcode = OpCode.Ping, data = ByteString("hello ping again"), maskingKey = Some(12345))
        assert(res2.stringData === "hello ping again")
        assert(res2.opcode === OpCode.Pong)

      }
      "auto ping" - doTwice(new ServerActor(autoPingInterval = 100 millis)){connection =>
        val res1 = connection await Tell(TestActorRef(new Actor{def receive = {case x =>}}), "lol", null)
        assert(res1.opcode === OpCode.Ping)
        val res2 = connection await Tell(TestActorRef(new Actor{def receive = {case x =>}}), "lol", null)
        assert(res2.opcode === OpCode.Ping)
      }

      class TimingEchoActor extends Actor{
        var lastDuration = Duration.Zero

        def receive = {
          case f @ Frame(fin, rsv, Text, maskingKey, data) =>
            println("A")
            sender ! Frame(fin, rsv, Text, None, lastDuration.toMillis.toString)
          case RoundTripTime(duration) =>
            println("B")
            lastDuration = duration
        }
      }
      "latency numbers" - doTwice(new ServerActor(autoPingInterval = 100 millis, echoActor = new TimingEchoActor())){connection =>
        {
          //initial latency should be 0
          val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res1.stringData === "0")

          // wait for ping and send pong
          val res2 = connection await Tell(TestActorRef(new Actor{def receive = {case x =>}}), "lol", null)
          assert(res2.opcode === OpCode.Ping)
          connection send Frame(opcode = OpCode.Pong, data = res2.data, maskingKey = Some(12345))

          //new latency should be non zero
          val res3 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res3.stringData.toLong > 0)
        }
        {
          // wait for ping and send pong
          val res2 = connection await Tell(TestActorRef(new Actor{def receive = {case x =>}}), "lol", null)
          assert(res2.opcode === OpCode.Ping)
          Thread.sleep(200)
          connection send Frame(opcode = OpCode.Pong, data = res2.data, maskingKey = Some(12345))

          //new latency should be non zero
          val res3 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res3.stringData.toLong > 200)
        }
      }
    }
  }
  "Echo Client Tests" - {
    "Hello World"
  }
}