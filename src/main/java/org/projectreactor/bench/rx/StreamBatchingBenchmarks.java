package org.projectreactor.bench.rx;

import org.openjdk.jmh.annotations.*;
import reactor.Processors;
import reactor.Timers;
import reactor.core.processor.ProcessorService;
import reactor.rx.broadcast.Broadcaster;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * from https://gist.github.com/oiavorskyi/a949aa6ef3556246c42d
 * issue https://github.com/reactor/reactor/issues/358
 *
 * @author Oleg Iavorskyi
 * @author Stephane Maldini
 *
 */
@Measurement(iterations = StreamBatchingBenchmarks.ITERATIONS, time = 30)
@Warmup(iterations = 1)
@Fork(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@OperationsPerInvocation(1000)
public class StreamBatchingBenchmarks {

	public final static int ITERATIONS = 5;

	@Param({"1000"})
	public int     elements;
	@Param({"false", "true"})
	public boolean filter;

	private String[]                    data;
	private Broadcaster<CountDownLatch> deferred;
	private CountDownLatch latch = new CountDownLatch(8);

	@Setup
	public void setup() {
		Timers.global();
		generateStream();

		//env.getRootTimer().schedule(i -> System.out.println(deferred.debug()), 200, TimeUnit.MILLISECONDS);

		data = new String[elements];
		for (int i = 0; i < elements; i++) {
			data[i] = Integer.toString(i);
		}
	}

	@TearDown(Level.Iteration)
	public void cleanLatch() throws InterruptedException {
		deferred.onComplete();
		if (!latch.await(30, TimeUnit.SECONDS))
			throw new RuntimeException(data.length + " elements trying to flow in, with latch count " + latch.getCount() +
					"\n " + deferred.debug().toString());

		generateStream();

	}

	@Benchmark
	public void composedStream() throws InterruptedException {
		for (String i : data) {
			deferred.onNext(latch);
		}
	}

	private void generateStream() {
		final Random random = new Random();


		//((WaitingMood)deferred.getDispatcher()).nervous();
		ProcessorService<CountDownLatch> dispatcherSupplier = Processors.asyncService("batch-stream", 2048, 9);

		deferred = Broadcaster.<CountDownLatch>create();
		deferred
		        .run(dispatcherSupplier)
				.partition(8)
				.consume(
				  stream ->
					(filter ?
					  stream.run(dispatcherSupplier).filter(i -> i.hashCode() != 0 ? true : true) :
					  stream.run(dispatcherSupplier)
					)
					  .buffer(elements / 8, 1000, TimeUnit.MILLISECONDS)
					  .consume(batch -> {
						  try {
							  Thread.sleep(random.nextInt(400) + 100);
						  } catch (InterruptedException e) {
							  e.printStackTrace();
						  }
						  for (CountDownLatch latch : batch) latch.countDown();

					  }, null, nothing -> latch.countDown())
				);
	}

}