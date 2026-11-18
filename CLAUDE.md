# CLAUDE.md: contexto del proyecto word2pdf

Documentación para usuarios: [README.md](README.md). Licencias: [LICENSING.md](LICENSING.md).
Idioma: documentación y mensajes en **español**; identificadores de código en inglés.

## Qué es

Microservicio Java 21 + Spring Boot 4.1.2 (Maven, con wrapper `mvnw`) que convierte contratos **DOCX → PDF** usando
**ONLYOFFICE Docs Community 10.0.0** (`onlyoffice/documentserver:10.0.0`) mediante `POST /converter/from-file`
(multipart, sin publicar archivos por URL). Recibe `byte[]` y devuelve `byte[]`, sin guardar nada en disco.
**Prioridad absoluta: fidelidad visual del PDF respecto al Word.**

Restricciones del cliente (no romperlas):
- No usar Aspose, librerías comerciales, Microsoft Office, LibreOffice, Apache POI para reconstruir el DOCX ni HTML intermedio.
- No modificar el DOCX antes de convertirlo. No fijar parámetros de maquetación en la conversión.
- No registrar contenido ni nombres de archivo de contratos en logs. No persistir documentos.
- Secretos solo por variables de entorno. ONLYOFFICE nunca expuesto públicamente.
- No afirmar fidelidad del 100 % ni declarar pruebas exitosas sin haberlas ejecutado.

## Arquitectura (`src/main/java/pe/saeta/word2pdf/`)

| Paquete | Clases clave |
|---|---|
| `controller` | `ConversionController`: `POST /api/v1/conversion/docx-to-pdf` (octet-stream), `/docx-to-pdf/multipart`, `/docx/inspect` |
| `service` | `DocumentConversionService` (contrato pedido por el cliente), `DefaultDocumentConversionService` (orquesta), `ConversionEngine` (puerto para cambiar de motor), `ConversionLimiter` (semáforo) |
| `client` | `OnlyOfficeClient` (JDK HttpClient, multipart `file` + `token`), `OnlyOfficeJwtSigner` (nimbus HS256, `operation=converter`, sin `url`), `OnlyOfficeErrorCode` (-1…-10), `OnlyOfficeHealthIndicator` |
| `config` | Records `@ConfigurationProperties` (`onlyoffice.*`, `app.conversion.*`, `app.security.*`), `ApiKeyAuthFilter` (`X-API-Key`), `RequestIdFilter` |
| `exception` | `ErrorCode` → HTTP, `ConversionException`, `GlobalExceptionHandler` (ProblemDetail RFC 9457) |
| `validation` | `DocxValidator` (ZIP/OOXML, anti zip-bomb, anti-XXE), `PdfValidator` (PDFBox 3.0.8 en memoria), `BoundedBodyReader` |
| `fidelity` | `DocxLayoutInspector` (tamaños de página y fuentes del DOCX), `FidelityChecker` (avisos `PAGE_SIZE_MISMATCH` / `FONT_SUBSTITUTION_SUSPECTED`) |
| `quality` | `PdfComparisonTool`: CLI de QA que compara un PDF de Word con el de ONLYOFFICE |

Notas de Spring Boot 4: Jackson 3 (`tools.jackson.*`), health en `org.springframework.boot.health.contributor`,
starters `spring-boot-starter-webmvc` / `-webmvc-test`, Testcontainers 2.x (`testcontainers-junit-jupiter`).

## Comandos

```powershell
.\mvnw.cmd verify                      # 113 pruebas sin Docker (ONLYOFFICE simulado: FakeOnlyOfficeServer)
.\mvnw.cmd -Pintegration verify        # + RealOnlyOfficeConversionIT con Testcontainers (requiere Docker)
scripts\generate-env.ps1               # crea .env con secretos (no versionado)
docker compose up -d --build           # servicio en 127.0.0.1:8080, ONLYOFFICE en red interna
scripts\e2e.ps1                        # compose + conversión de samples\contrato-ejemplo.docx → out\
scripts\check-fonts.ps1 -Docx x.docx   # fuentes del contenedor vs fuentes del DOCX
scripts\compare-pdf.ps1 -Reference word.pdf -Candidate onlyoffice.pdf
```

Desarrollo con el JDK local: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d onlyoffice`
(publica ONLYOFFICE en `127.0.0.1:8081`) y `ONLYOFFICE_URL=http://localhost:8081` + `.\mvnw.cmd spring-boot:run`.

Los DOCX de prueba se generan con `src/test/java/.../support/SampleDocxGenerator.java` (OOXML escrito a mano, contenido ficticio).

## Estado al 2026-11-17

Verificado **con Docker** (Engine 29.9.2). Detalle en la tabla "Estado de verificación" del README:
- `mvnw verify`: 113 pruebas, 0 fallos. `-Pintegration verify`: además, 6 pruebas reales con ONLYOFFICE 10.0.0, 0 fallos.
- El transporte JWT `FORM_PART` funciona con 10.0.0 (Testcontainers y Compose). `HEADER` no se ha probado contra el servidor real.
- Imagen Docker, e2e con Compose, contenedor `read_only` y logs sin contenido: correctos.
- Caché: cada conversión deja `origin.docx` + `output.pdf` en `cache/files/data/conv_<id>/`. La retención de
  `local-production-linux.json` **no es fiable**, así que `docker-compose.yml` monta esa ruta como **`tmpfs`** (verificado).
- Fuentes: la imagen no tiene `fc-list`. Trae Calibri y Cambria, pero **no Aptos**. Las fuentes montadas en `fonts/`
  se registran al reiniciar y se incrustan en el PDF.
- Fidelidad frente a Word con 1 pagaré real: 4 = 4 páginas, 149 de 151 líneas coinciden, desplazamiento máximo 0,1 pt.
- Arreglos hechos: `FakeOnlyOfficeServer` (medición de concurrencia que hacía fallar `OverloadApiTest` de forma
  intermitente) y `PdfComparisonTool` (ahora empareja líneas completas; antes, fragmentos de texto).
- La automatización COM de Word se cuelga en esta PC: el usuario exporta los PDF de referencia a mano
  (Archivo → Exportar → Crear documento PDF/XPS).

## PENDIENTE

1. **Fidelidad con más contratos reales:** comparar 5-10 contratos variados con `scripts\compare-pdf.ps1`
   (los archivos van en `archivos de prueba/`, ignorada por git). Después, calibrar `--pixel-threshold`: con un 2 %,
   el pagaré se marcó por una sola diferencia de salto de línea. Revisar también falsos positivos de
   `FONT_SUBSTITUTION_SUSPECTED`. Los contratos reales NO deben subirse al repositorio.
2. Si los contratos usan **Aptos**, conseguirla con licencia y montarla en `fonts/`.
3. Opcional: probar `ONLYOFFICE_JWT_TRANSPORT=HEADER` contra el servidor real. Valorar `tmpfs` también para `/tmp` de
   ONLYOFFICE (requiere `exec`, porque `/tmp/.cache` carga módulos nativos).

Mejoras opcionales, no pedidas aún: TLS mediante proxy inverso, endpoint Prometheus, informe de licencias con
`license-maven-plugin`, CI en GitHub Actions (`mvnw verify` sin Docker).
