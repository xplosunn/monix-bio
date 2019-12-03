package monix.bio.internal

import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.AbstractQueuedSynchronizer

import monix.bio.WRYYY
import monix.bio.WRYYY.{Async, Context, Error, Eval, FatalError, FlatMap, Map, Now, Suspend}
import monix.bio.internal.TaskRunLoop._
import monix.execution.internal.collection.ChunkedArrayStack
import monix.execution.{Callback, Scheduler}

import scala.concurrent.blocking
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

private[bio] object TaskRunSyncUnsafe {
  /** Run-loop specialization that evaluates the given task and blocks for the result
    * if the given task is asynchronous.
    */
  def apply[E, A](source: WRYYY[E, A], timeout: Duration, scheduler: Scheduler, opts: WRYYY.Options): A = {
    var current = source.asInstanceOf[WRYYY[Any, Any]]
    var bFirst: Bind = null
    var bRest: CallStack = null
    // Values from Now, Always and Once are unboxed in this var, for code reuse
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    do {
      current match {
        case FlatMap(fa, bindNext) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = ChunkedArrayStack()
            bRest.push(bFirst)
          }
          /*_*/
          bFirst = bindNext /*_*/
          current = fa

        case Now(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Eval(thunk) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
          } catch {
            case e if NonFatal(e) =>
              current = FatalError(e)
          }

        case bindNext @ Map(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = ChunkedArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext
          current = fa

        case Suspend(thunk) =>
          // Try/catch described as statement to prevent ObjectRef ;-)
          try {
            current = thunk()
          } catch {
            case ex if NonFatal(ex) => current = FatalError(ex)
          }

        case Error(error) =>
          findErrorHandler[Any](bFirst, bRest) match {
            case null => throw new WrappedException(error)
            case bind =>
              // Try/catch described as statement to prevent ObjectRef ;-)
              try {
                current = bind.recover(error)
              } catch { case e if NonFatal(e) => current = FatalError(e) }
              bFirst = null
          }

        case FatalError(error) =>
          findFatalErrorHandler[Any](bFirst, bRest) match {
            case null => throw error
            case bind =>
              // Try/catch described as statement to prevent ObjectRef ;-)
              try {
                current = bind.recover(error)
              } catch { case e if NonFatal(e) => current = FatalError(e) }
              bFirst = null
          }

        case async =>
          return blockForResult(async, timeout, scheduler, opts, bFirst, bRest)
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            return unboxed.asInstanceOf[A]
          case bind =>
            // Try/catch described as statement to prevent ObjectRef ;-)
            try {
              current = bind(unboxed)
            } catch {
              case ex if NonFatal(ex) => current = FatalError(ex)
            }
            hasUnboxed = false
            unboxed = null
            bFirst = null
        }
      }
    } while (true)
    // $COVERAGE-OFF$
    throw new IllegalStateException("out of loop")
    // $COVERAGE-ON$
  }

  private def blockForResult[A](
    source: WRYYY[Any, Any],
    limit: Duration,
    scheduler: Scheduler,
    opts: WRYYY.Options,
    bFirst: Bind,
    bRest: CallStack): A = {

    val latch = new OneShotLatch
    val cb = new BlockingCallback[Any, Any](latch)
    val context = Context[Any](scheduler, opts)

    // Starting actual execution
    val rcb = TaskRestartCallback(context, cb)
    source match {
      case async: Async[Any, Any] @unchecked =>
        executeAsyncTask(async, context, cb, rcb, bFirst, bRest, 1)
      case _ =>
        startFull(source, context, cb, rcb, bFirst, bRest, 1)
    }

    val isFinished = limit match {
      case e if e eq Duration.Undefined =>
        throw new IllegalArgumentException("Cannot wait for Undefined period")
      case Duration.Inf =>
        blocking(latch.acquireSharedInterruptibly(1))
        true
      case f: FiniteDuration if f > Duration.Zero =>
        blocking(latch.tryAcquireSharedNanos(1, f.toNanos))
      case _ =>
        false
    }

    if (isFinished)
      cb.value.asInstanceOf[A]
    else
      throw new TimeoutException(s"Task.runSyncUnsafe($limit)")
  }

  private final class BlockingCallback[E, A](latch: OneShotLatch) extends Callback[E, A] {

    private[this] var success: A = _
    private[this] var error: E = _

    def value: A =
      error match {
        case null => success
        case e => throw new WrappedException(e)
      }

    def onSuccess(value: A): Unit = {
      success = value
      latch.releaseShared(1)
    }

    def onError(ex: E): Unit = {
      error = ex
      latch.releaseShared(1)
    }
  }

  private final class OneShotLatch extends AbstractQueuedSynchronizer {
    override protected def tryAcquireShared(ignored: Int): Int =
      if (getState != 0) 1 else -1

    override protected def tryReleaseShared(ignore: Int): Boolean = {
      setState(1)
      true
    }
  }
}