package pe.saeta.word2pdf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Word2PdfApplication {

    public static void main(String[] args) {
        SpringApplication.run(Word2PdfApplication.class, args);
    }
}
