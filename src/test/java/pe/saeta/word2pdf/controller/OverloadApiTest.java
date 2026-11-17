package pe.saeta.word2pdf.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import pe.saeta.word2pdf.support.FakeOnlyOfficeServer.Behavior;
import pe.saeta.word2pdf.support.SampleDocxGenerator;

/** Saturación: las solicitudes que no consiguen cupo a tiempo reciben 503 + Retry-After. */
@TestPropertySource(properties = {
        "app.conversion.max-concurrent=1",
        "app.conversion.queue-timeout=150ms"
})
class OverloadApiTest extends ApiTestSupport {

    @Test
    void excessRequestsAreRejectedWith503() throws Exception {
        FAKE.setBehavior(Behavior.pdf(1).delayed(1200));
        byte[] docx = SampleDocxGenerator.minimal();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        int ok = 0;
        int rejected = 0;
        try {
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> postBinary(docx)));
            }
            for (Future<HttpResponse<byte[]>> f : futures) {
                HttpResponse<byte[]> response = f.get(60, TimeUnit.SECONDS);
                if (response.statusCode() == 200) {
                    ok++;
                } else {
                    assertThat(response.statusCode()).isEqualTo(503);
                    assertThat(response.headers().firstValue("Retry-After")).isPresent();
                    assertThat(json(response.body()).get("code").asString()).isEqualTo("TOO_MANY_CONVERSIONS");
                    rejected++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok).isGreaterThanOrEqualTo(1);
        assertThat(rejected).isGreaterThanOrEqualTo(1);
        assertThat(FAKE.maxConcurrent()).isEqualTo(1);
    }
}
