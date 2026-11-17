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

/** Conversiones simultáneas: todas terminan bien y el motor nunca recibe más de max-concurrent a la vez. */
@TestPropertySource(properties = {
        "app.conversion.max-concurrent=2",
        "app.conversion.queue-timeout=20s"
})
class ConcurrentConversionApiTest extends ApiTestSupport {

    @Test
    void parallelRequestsAreQueuedAndAllSucceed() throws Exception {
        FAKE.setBehavior(Behavior.pdf(2).delayed(300));
        byte[] docx = SampleDocxGenerator.fullContract();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> postBinary(docx)));
            }
            for (Future<HttpResponse<byte[]>> f : futures) {
                HttpResponse<byte[]> response = f.get(60, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(new String(response.body(), 0, 5)).isEqualTo("%PDF-");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(FAKE.requests()).isEqualTo(8);
        assertThat(FAKE.maxConcurrent()).isLessThanOrEqualTo(2);
    }
}
