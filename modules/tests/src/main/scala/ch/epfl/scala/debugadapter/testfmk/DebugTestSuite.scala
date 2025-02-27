package ch.epfl.scala.debugadapter.testfmk

import ch.epfl.scala.debugadapter.DebugTools
import ch.epfl.scala.debugadapter.DebugServer
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import com.microsoft.java.debug.core.protocol.Types.StackFrame
import ch.epfl.scala.debugadapter.GithubUtils
import ch.epfl.scala.debugadapter.Debuggee
import scala.concurrent.duration.*
import ch.epfl.scala.debugadapter.Logger
import java.net.URI
import com.microsoft.java.debug.core.protocol.Events.OutputEvent.Category
import munit.FunSuite
import munit.Assertions.*
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint
import ch.epfl.scala.debugadapter.DebugConfig

abstract class DebugTestSuite extends FunSuite with DebugTest {
  override def munitTimeout: Duration = 120.seconds
}

trait DebugTest {
  // the server needs only one thread for delayed responses of the launch and configurationDone requests
  val executorService = Executors.newFixedThreadPool(1)
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

  protected def defaultConfig: DebugConfig = DebugConfig.default.copy(autoCloseSession = false, testMode = true)

  def isScala3(implicit ctx: TestingContext) = ctx.scalaVersion.isScala3
  def isScala2(implicit ctx: TestingContext) = ctx.scalaVersion.isScala2
  def isScala213(implicit ctx: TestingContext) = ctx.scalaVersion.isScala213
  def isScala212(implicit ctx: TestingContext) = ctx.scalaVersion.isScala212
  def isScala30(implicit ctx: TestingContext) = ctx.scalaVersion.isScala30
  def isScala32(implicit ctx: TestingContext) = ctx.scalaVersion.isScala32

  def getDebugServer(
      debuggee: Debuggee,
      config: DebugConfig = defaultConfig,
      logger: Logger = NoopLogger
  ): DebugServer = {
    val tools = DebugTools(debuggee, TestingResolver, logger)
    DebugServer(debuggee, tools, logger, config = config)
  }

  def startDebugServer(
      debuggee: Debuggee,
      gracePeriod: Duration = 2.seconds,
      logger: Logger = NoopLogger
  ): DebugServer.Handler = {
    val tools = DebugTools(debuggee, TestingResolver, logger)
    DebugServer.start(debuggee, tools, logger)
  }

  def check(uri: URI, attach: Option[Int] = None)(steps: DebugStepAssert[?]*): Unit = {
    val client = TestingDebugClient.connect(uri)
    try check(client, attach)(steps*)
    finally client.close()
  }

  def check(steps: DebugStepAssert[?]*)(implicit debuggee: TestingDebuggee): Unit = {
    val server = getDebugServer(debuggee)
    val client = TestingDebugClient.connect(server.uri)
    try {
      server.connect()
      check(client, None)(steps*)
    } finally {
      client.close()
      server.close()
    }
  }

  private def check(client: TestingDebugClient, attach: Option[Int])(steps: DebugStepAssert[?]*): Unit = {
    client.initialize()
    attach match {
      case Some(port) => client.attach("localhost", port)
      case None => client.launch()
    }

    val sourceBreakpoints = steps
      .map(_.step)
      .distinct
      .collect {
        case b: Breakpoint =>
          b.sourceFile -> new SourceBreakpoint(b.line, b.condition.getOrElse(null), null)
        case l: Logpoint =>
          val breakpoint = new SourceBreakpoint(l.line, null, null)
          breakpoint.logMessage = l.logMessage
          l.sourceFile -> breakpoint
      }

    sourceBreakpoints
      .groupBy(_._1)
      .map { case (sourceFile, bs) => (sourceFile, bs.map(_._2)) }
      .foreach { case (sourceFile, sourceBreakpoints) =>
        sourceBreakpoints
          .groupBy(_.line)
          .foreach { case (line, bs) =>
            assert(bs.size == 1, s"More than one breakpoint in $sourceFile on line $line")
          }
        val configuredBreakpoints = client.setSourceBreakpoints(sourceFile, sourceBreakpoints)
        assertEquals(configuredBreakpoints.length, sourceBreakpoints.length)
        assert(configuredBreakpoints.forall(_.verified))
      }
    client.configurationDone()

    var threadId: Long = -1
    var topFrame: StackFrame = null
    var paused = false
    def continueIfPaused(): Unit = {
      if (paused) {
        client.continue(threadId)
        paused = false
      }
    }
    def assertStop(assertion: StackFrame => Unit): Unit = {
      val stopped = client.stopped()
      paused = true
      threadId = stopped.threadId
      val stackTrace = client.stackTrace(threadId)
      topFrame = stackTrace.stackFrames.head
      assertion(topFrame)
    }
    steps.foreach {
      case DebugStepAssert(_: Breakpoint, assertion) =>
        continueIfPaused()
        assertStop(assertion)
      case DebugStepAssert(_: Logpoint, assertion) =>
        continueIfPaused()
        // A log point needs time for evaluation
        val event = client.outputed(m => m.category == Category.stdout, 16.seconds)
        print(s"> ${event.output}")
        assertion(event.output.trim)
      case DebugStepAssert(_: StepIn, assertion) =>
        println(s"Stepping in, at ${topFrame.source.name} ${topFrame.line}")
        client.stepIn(threadId)
        assertStop(assertion)
      case DebugStepAssert(_: StepOut, assertion) =>
        println(s"Stepping out, at ${topFrame.source.name} ${topFrame.line}")
        client.stepOut(threadId)
        assertStop(assertion)
      case DebugStepAssert(_: StepOver, assertion) =>
        ???
      case DebugStepAssert(eval: Evaluation, assertion) =>
        println(s"$$ ${eval.expression}")
        val response = client.evaluate(eval.expression, topFrame.id)
        response.foreach(res => println(s"> $res"))
        assertion(response)
      case DebugStepAssert(Outputed(), assertion) =>
        continueIfPaused()
        val event = client.outputed(m => m.category == Category.stdout)
        print(s"> ${event.output}")
        assertion(event.output.trim)
      case DebugStepAssert(_: NoStep, _) => ()
    }
    continueIfPaused()

    // This is flaky, terminated can happen before exited
    if (!GithubUtils.isCI()) {
      client.exited()
      client.terminated()
    }
  }
}

object DebugTest extends DebugTest
