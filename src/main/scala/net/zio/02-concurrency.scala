/**
 * CONCURRENCY
 *
 * ZIO has pervasive support for concurrency, including parallel versions of
 * operators like `zip`, `foreach`, and many others.
 *
 * More than just enabling your applications to be highly concurrent, ZIO
 * gives you lock-free, asynchronous structures like queues, hubs, and pools.
 * Sometimes you need more: you need the ability to concurrently update complex
 * shared state across many fibers.
 *
 * Whether you need the basics or advanced custom functionality, ZIO has the
 * toolset that enables you to solve any problem you encounter in the
 * development of highly-scalable, resilient, cloud-native applications.
 *
 * In this section, you will explore more advanced aspects of ZIO concurrency,
 * so you can learn to build applications that are low-latency, highly-
 * scalable, interruptible, and free from deadlocks and race conditions.
 */
package advancedzio.concurrency

import zio._
import zio.stm._
import zio.test._
import zio.test.TestAspect._
import zio.test.environment.Live

/**
 * ZIO queues are high-performance, asynchronous, lock-free structures backed
 * by hand-optimized ring buffers. ZIO queues come in variations for bounded,
 * which are doubly-backpressured for producers and consumers, sliding (which
 * drops earlier elements when capacity is reached), dropping (which drops
 * later elements when capacity is reached), and unbounded.
 *
 * Queues work well for multiple producers and multiple consumers, where
 * consumers divide work between themselves.
 */
object QueueBasics extends DefaultRunnableSpec {
  def spec =
    suite("QueueBasics") {

      /**
       * EXERCISE
       *
       * Create a consumer of this queue that adds each value taken from the
       * queue to the counter, so the unit test can pass.
       */
      test("consumer") {
        for {
          counter <- Ref.make(0)
          queue   <- Queue.bounded[Int](100)
          _       <- ZIO.foreach(1 to 100)(v => queue.offer(v)).forkDaemon
          value   <- counter.get
        } yield assertTrue(value == 5050)
      } @@ ignore +
        /**
         * EXERCISE
         *
         * Queues are fully concurrent-safe on both producer and consumer side.
         * Choose the appropriate operator to parallelize the production side so
         * all values are produced in parallel.
         */
        test("multiple producers") {
          for {
            counter <- Ref.make(0)
            queue   <- Queue.bounded[Int](100)
            _       <- ZIO.foreach(1 to 100)(v => queue.offer(v)).forkDaemon
            _       <- queue.take.flatMap(v => counter.update(_ + v)).repeatN(99)
            value   <- counter.get
          } yield assertTrue(value == 5050)
        } @@ ignore +
        /**
         * EXERCISE
         *
         * Choose the appropriate operator to parallelize the consumption side so
         * all values are consumed in parallel.
         */
        test("multiple consumers") {
          for {
            counter <- Ref.make(0)
            queue   <- Queue.bounded[Int](100)
            _       <- ZIO.foreachPar(1 to 100)(v => queue.offer(v)).forkDaemon
            _       <- queue.take.flatMap(v => counter.update(_ + v)).repeatN(99)
            value   <- counter.get
          } yield assertTrue(value == 5050)
        } @@ ignore
    }
}

/**
 * ZIO's software transactional memory lets you create your own custom
 * lock-free, race-free, concurrent structures, for cases where there
 * are no alternatives in ZIO, or when you need to make coordinated
 * changes across many structures in a transactional way.
 */
object StmBasics extends DefaultRunnableSpec {
  def spec =
    suite("StmBasics") {
      test("permits") {

        /**
         * EXERCISE
         *
         * Implement `acquire` and `release` in a fashion the test passes.
         */
        final case class Permits(ref: TRef[Int]) {
          def acquire(howMany: Int): UIO[Unit] = ???

          def release(howMany: Int): UIO[Unit] = ???
        }

        def makePermits(max: Int): UIO[Permits] = TRef.make(max).map(Permits(_)).commit

        for {
          counter <- Ref.make(0)
          permits <- makePermits(100)
          _ <- ZIO.foreachPar(1 to 1000)(
                _ => Random.nextIntBetween(1, 2).flatMap(n => permits.acquire(n) *> permits.release(n))
              )
          latch   <- Promise.make[Nothing, Unit]
          fiber   <- (latch.succeed(()) *> permits.acquire(101) *> counter.set(1)).forkDaemon
          _       <- latch.await
          _       <- Live.live(ZIO.sleep(1.second))
          _       <- fiber.interrupt
          count   <- counter.get
          permits <- permits.ref.get.commit
        } yield assertTrue(count == 0 && permits == 100)
      }
    }
}

/**
 * ZIO hubs are high-performance, asynchronous, lock-free structures backed
 * by hand-optimized ring buffers. Hubs are designed for broadcast scenarios
 * where multiple (potentially many) consumers need to access the same values
 * being published to the hub.
 */
object HubBasics extends DefaultRunnableSpec {
  def spec =
    suite("HubBasics") {

      /**
       * EXERCISE
       *
       * Use the `subscribe` method from 100 fibers to pull out the same values
       * from a hub, and use those values to increment `counter`.
       *
       * Take note of the synchronization logic. Why is this logic necessary?
       */
      test("subscribe") {
        for {
          counter <- Ref.make[Int](0)
          hub     <- Hub.bounded[Int](100)
          latch   <- TRef.make(100).commit
          scount  <- Ref.make[Int](0)
          _       <- (latch.get.retryUntil(_ <= 0).commit *> ZIO.foreach(1 to 100)(hub.publish(_))).forkDaemon
          _ <- ZIO.foreachPar(1 to 100) { _ =>
                hub.subscribe.use { queue =>
                  latch.update(_ - 1).commit
                }
              }
          value <- counter.get
        } yield assertTrue(value == 505000)
      } @@ ignore
    }
}
