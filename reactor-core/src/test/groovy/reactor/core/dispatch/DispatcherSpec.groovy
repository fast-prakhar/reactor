/*
 * Copyright (c) 2011-2015 Pivotal Software Inc., Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package reactor.core.dispatch

import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.Environment
import reactor.bus.Event
import reactor.bus.EventBus
import reactor.core.Dispatcher
import reactor.fn.Consumer
import reactor.jarjar.com.lmax.disruptor.BlockingWaitStrategy
import reactor.jarjar.com.lmax.disruptor.dsl.ProducerType
import reactor.rx.broadcast.Broadcaster
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static reactor.GroovyTestUtils.$
import static reactor.GroovyTestUtils.consumer
import static reactor.bus.selector.Selectors.T

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
class DispatcherSpec extends Specification {

	@Shared
	Environment env

	def setup() {
		env = new Environment()
	}

	def cleanup() {
		env.shutdown()
	}

	def "Dispatcher executes tasks in correct thread"() {

		given:
			def sameThread = new SynchronousDispatcher()
			def diffThread = new ThreadPoolExecutorDispatcher(1, 128)
			def currentThread = Thread.currentThread()
			Thread taskThread = null
			def consumer = { ev ->
				taskThread = Thread.currentThread()
			}

		when:
			"a task is submitted"
			sameThread.dispatch('test', consumer, null)

		then:
			"the task thread should be the current thread"
			currentThread == taskThread

		when:
			"a task is submitted to the thread pool dispatcher"
			def latch = new CountDownLatch(1)
			diffThread.dispatch('test', { ev -> consumer(ev); latch.countDown() }, null)

			latch.await(5, TimeUnit.SECONDS) // Wait for task to execute

		then:
			"the task thread should be different when the current thread"
			taskThread != currentThread
			//!diffThread.shutdown()

	}

	def "Dispatcher thread can be reused"() {

		given:
			"ring buffer eventBus"
			def r = EventBus.config().env(env).dispatcher(Environment.SHARED).get()
			def latch = new CountDownLatch(2)

		when:
			"listen for recursive event"
			r.on($('test')) { Event<Integer> ev ->
				if (ev.data < 2) {
					latch.countDown()
					r.notify('test', Event.wrap(++ev.data))
				}
			}

		and:
			"call the eventBus"
			r.notify('test', Event.wrap(0))

		then:
			"a task is submitted to the thread pool dispatcher"
			latch.await(5, TimeUnit.SECONDS) // Wait for task to execute
	}

	@Ignore
	def "Dispatcher on Reactive Stream"() {

		given:
			"ring buffer dispatcher"
			def dispatcher = (RingBufferDispatcher) env.cachedDispatcher
			def dispatcher2 = (RingBufferDispatcher) env.cachedDispatcher
			println dispatcher.backlogSize()
			def bc = Broadcaster.<String> create()
			def bc2 = Broadcaster.<String> create()
			def elems = 18
			def latch = new CountDownLatch(elems)
			def name = 'spec1'
			def sub = { new Subscriber<String>() {
				def s

				@Override
				void onSubscribe(Subscription s) {
					this.s = s
					println name +" "+Thread.currentThread().name + ": subscribe: " + s
					s.request(1)
				}

				@Override
				void onNext(String o) {
					println name +" "+Thread.currentThread().name + ": next: " + o
					latch.countDown()
					s.request(2)
				}

				@Override
				void onError(Throwable t) {
					println name +" "+Thread.currentThread().name + ": error:" + t
				}

				@Override
				void onComplete() {
					println name +" "+Thread.currentThread().name + ": complete"
					//latch.countDown()
				}
			}}

			bc.subscribe(dispatcher.dispatch(bc2))
			bc2.subscribe(dispatcher2.dispatch(sub()))
		println bc.debug()
		println bc2.debug()

		when:
			"call the broadcaster"
			elems.times {
				bc.onNext 'hello ' + it
			}
			//bc.onComplete()
			println bc.debug()
			println bc2.debug()

			def ended = latch.await(50, TimeUnit.SECONDS) // Wait for task to execute
			println bc.debug()
			println bc2.debug()

		then:
			"a task is submitted to the thread pool dispatcher"
		ended
			//!bc.downstreamSubscription()

		when:
			latch = new CountDownLatch(elems)
			name = "spec2"
			bc = Broadcaster.create()
			bc.subscribe(dispatcher.dispatch(sub()))

			elems.times {
				bc.onNext 'hello ' + it
			}
			bc.onComplete()

		then:
			"a task is submitted to the thread pool dispatcher"
			latch.await(50, TimeUnit.SECONDS) // Wait for task to execute
	}

	def "Dispatchers can be shutdown awaiting tasks to complete"() {

		given:
			"a Reactor with a ThreadPoolExecutorDispatcher"
			def r = EventBus.config().
					env(env).
					dispatcher(Environment.THREAD_POOL).
					get()
			long start = System.currentTimeMillis()
			def hello = ""
			r.on($("pause"), { Event<String> ev ->
				hello = ev.data
				Thread.sleep(1000)
			} as Consumer<Event<?>>)

		when:
			"the Dispatcher is shutdown and tasks are awaited"
			r.notify("pause", Event.wrap("Hello World!"))
			def success = r.dispatcher.awaitAndShutdown(5, TimeUnit.SECONDS)
			long end = System.currentTimeMillis()

		then:
			"the Consumer was run, this thread was blocked, and the Dispatcher is shut down"
			hello == "Hello World!"
			success
			(end - start) >= 1000

	}

	def "RingBufferDispatcher doesn't deadlock on thrown Exception"() {

		given:
			def dispatcher = new RingBufferDispatcher("rb", 8, null, ProducerType.MULTI, new BlockingWaitStrategy())
			def r = new EventBus(dispatcher)

		when:
			def stream = Broadcaster.<Throwable> create()
			def promise = stream.take(16).count().toList()
			r.on(T(Throwable), stream.toBroadcastNextConsumer())
			r.on($("test"), { ev ->
				sleep(100)
				1 / 0
			} as Consumer<Event<String>>)
			16.times {
				r.notify "test", Event.wrap("test")
			}
			println stream.debug()

		then:
			promise.await(5, TimeUnit.SECONDS)
			promise.get() == [16]

	}

	def "RingBufferDispatcher executes tasks in correct thread"() {

		given:
			def dispatcher = new RingBufferDispatcher("rb", 8, null, ProducerType.MULTI, new BlockingWaitStrategy())
			def t1 = Thread.currentThread()
			def t2 = Thread.currentThread()

		when:
			dispatcher.execute({ t2 = Thread.currentThread() })
			Thread.sleep(500)

		then:
			t1 != t2

	}

	def "WorkQueueDispatcher executes tasks in correct thread"() {

		given:
			def dispatcher = new WorkQueueDispatcher("rb", 8, 1024, null, ProducerType.MULTI, new BlockingWaitStrategy())
			def t1 = Thread.currentThread()
			def t2 = Thread.currentThread()

		when:
			dispatcher.execute({ t2 = Thread.currentThread() })
			Thread.sleep(500)

		then:
			t1 != t2

	}

	def "MultiThreadDispatchers support ping pong dispatching"(Dispatcher d) {

		given:
			def r = EventBus.create(env, d)
			def latch = new CountDownLatch(4)
			def main = Thread.currentThread()
			def t1 = Thread.currentThread()
			def t2 = Thread.currentThread()

		when:
			r.on($("ping"), consumer {
				if (latch.count > 0) {
					t1 = Thread.currentThread()
					r.notify("pong")
					latch.countDown()
				}
			})
			r.on($("pong"), {
				if (latch.count > 0) {
					t2 = Thread.currentThread()
					r.notify("ping")
					latch.countDown()
				}
			})
			r.notify("ping")

		then:
			latch.await(1, TimeUnit.SECONDS)
			main != t1
			main != t2
			t1 != t2

		where:
			d << [
					new ThreadPoolExecutorDispatcher(4, 1024),
					new WorkQueueDispatcher("ping-pong", 4, 1024, null)
			]

	}

}
