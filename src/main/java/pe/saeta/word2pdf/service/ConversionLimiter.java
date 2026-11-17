package pe.saeta.word2pdf.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;

/**
 * Limita las conversiones simultáneas hacia el motor para proteger CPU y RAM (de este servicio y de ONLYOFFICE).
 * Las solicitudes que no obtienen cupo dentro de {@code queue-timeout} reciben 503 con Retry-After.
 */
@Component
public class ConversionLimiter {

    private final Semaphore permits;
    private final int maxConcurrent;
    private final long queueTimeoutMillis;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Counter rejected;

    public ConversionLimiter(ConversionProperties properties, MeterRegistry registry) {
        this.maxConcurrent = properties.maxConcurrent();
        this.permits = new Semaphore(maxConcurrent, true);
        this.queueTimeoutMillis = properties.queueTimeout().toMillis();
        this.rejected = Counter.builder("docconv.conversion.rejected")
                .description("Conversiones rechazadas por falta de capacidad").register(registry);
        Gauge.builder("docconv.conversion.in_flight", inFlight, AtomicInteger::get)
                .description("Conversiones en curso").register(registry);
    }

    public <T> T execute(Supplier<T> task) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(queueTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException(ErrorCode.TOO_MANY_CONVERSIONS, null, "Interrumpido esperando cupo", e);
        }
        if (!acquired) {
            rejected.increment();
            throw new ConversionException(ErrorCode.TOO_MANY_CONVERSIONS, null,
                    "Sin cupo tras " + queueTimeoutMillis + " ms (max-concurrent=" + maxConcurrent + ")");
        }
        inFlight.incrementAndGet();
        try {
            return task.get();
        } finally {
            inFlight.decrementAndGet();
            permits.release();
        }
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }
}
