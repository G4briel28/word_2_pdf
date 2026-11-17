package pe.saeta.word2pdf.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.support.TestConfigs;

class ConversionLimiterTest {

    @Test
    void neverExceedsMaxConcurrent() throws Exception {
        ConversionLimiter limiter = new ConversionLimiter(TestConfigs.conversion(2, Duration.ofSeconds(10)),
                new SimpleMeterRegistry());
        AtomicInteger current = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> limiter.execute(() -> {
                    max.accumulateAndGet(current.incrementAndGet(), Math::max);
                    sleep(100);
                    current.decrementAndGet();
                    return 1;
                })));
            }
            for (Future<Integer> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(max.get()).isEqualTo(2);
        assertThat(limiter.availablePermits()).isEqualTo(2);
        assertThat(limiter.inFlight()).isZero();
    }

    @Test
    void rejectsWhenQueueTimeoutExpires() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversionLimiter limiter = new ConversionLimiter(TestConfigs.conversion(1, Duration.ofMillis(100)), registry);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> limiter.execute(() -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holding.await();
        assertThatThrownBy(() -> limiter.execute(() -> 1))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TOO_MANY_CONVERSIONS));
        release.countDown();
        holder.join();
        assertThat(registry.get("docconv.conversion.rejected").counter().count()).isEqualTo(1.0);
        assertThat(limiter.execute(() -> 2)).isEqualTo(2);
    }

    @Test
    void releasesPermitWhenTaskFails() {
        ConversionLimiter limiter = new ConversionLimiter(TestConfigs.conversion(1, Duration.ofMillis(100)),
                new SimpleMeterRegistry());
        assertThatThrownBy(() -> limiter.execute(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
