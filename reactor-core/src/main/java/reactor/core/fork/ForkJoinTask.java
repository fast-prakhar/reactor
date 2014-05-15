package reactor.core.fork;

import com.gs.collections.api.list.MutableList;
import com.gs.collections.impl.block.procedure.checked.CheckedProcedure;
import com.gs.collections.impl.list.mutable.MultiReaderFastList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.function.Consumer;
import reactor.function.Function;
import reactor.rx.Promise;
import reactor.rx.Stream;
import reactor.rx.action.Pipeline;

import java.util.concurrent.Executor;

/**
 * Represents a collection of asynchronous tasks that will be executed once per {@link #submit()}.
 *
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class ForkJoinTask<T, C extends Pipeline<T>> implements Consumer<Object> {

	private final Logger                              log   = LoggerFactory.getLogger(getClass());
	private final MultiReaderFastList<Function<?, ?>> tasks = MultiReaderFastList.newList();

	private final Executor       executor;
	private final Pipeline<T> deferred;

	ForkJoinTask(Executor executor, Pipeline<T> deferred) {
		this.executor = executor;
		this.deferred = deferred;
	}

	/**
	 * Get the {@link com.gs.collections.api.list.MutableList} of tasks that will be submitted when {@link
	 * #submit(Object)} is called.
	 *
	 * @return
	 */
	public MutableList<Function<?, ?>> getTasks() {
		return tasks;
	}

	/**
	 * Add a task to the collection of tasks to be executed.
	 *
	 * @param fn
	 * 		task to submit
	 * @param <V>
	 * 		type of result
	 *
	 * @return {@code this}
	 */
	public <V> ForkJoinTask<T, C> add(Function<V, T> fn) {
		tasks.add(fn);
		return this;
	}

	/**
	 * Compose actions against the asynchronous results.
	 *
	 * @return {@link reactor.rx.Promise} or a {@link reactor.rx.Stream} depending on the
	 * implementation.
	 */
	public Pipeline<T> compose() {
		return deferred;
	}

	/**
	 * Submit all configured tasks, possibly in parallel (depending on the configuration of the {@link
	 * java.util.concurrent.Executor} in use), passing {@code null} as an input parameter.
	 */
	public void submit() {
		accept(null);
	}

	/**
	 * Submit all configured tasks, possibly in parallel (depending on the configuration of the {@link
	 * java.util.concurrent.Executor} in use), passing {@code arg} as an input parameter.
	 *
	 * @param arg
	 * 		input parameter
	 * @param <V>
	 * 		type of input parameter
	 */
	public <V> void submit(V arg) {
		accept(arg);
	}

	@Override
	public void accept(final Object arg) {
		tasks.forEach(new CheckedProcedure<Function>() {
			@SuppressWarnings("unchecked")
			@Override
			public void safeValue(final Function fn) throws Exception {
				executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							Object result = fn.apply(arg);
							if (null != result) {
								deferred.broadcastNext((T) result);
							}
						} catch (Exception e) {
							if (compose() instanceof Stream || !((Promise) compose()).isComplete()) {
								deferred.broadcastError(e);
							} else {
								log.error(e.getMessage(), e);
							}
						}
					}
				});
			}
		});
	}

}
