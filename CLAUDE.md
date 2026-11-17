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
.\mvnw.cmd verify                      # 112 pruebas sin Docker (ONLYOFFICE simulado: FakeOnlyOfficeServer)
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

Hecho y verificado en una PC **sin Docker**:
- Compilación y `mvnw verify`: 112 pruebas, 0 fallos.
- Prueba de humo del JAR (arranque, health, 401, 422, 503, `/inspect`, logs sin contenido).
- CLI `PdfComparisonTool` vía `PropertiesLauncher`.

## PENDIENTE (requiere Docker; hacerlo en este orden)

1. **Integración real:** `.\mvnw.cmd -Pintegration verify`. Revisar los PDF en `target/it-output/` y abrirlos para inspección visual.
   - Si falla con error **-7 o -8**: el transporte JWT por parte multipart `token` (`FORM_PART`) no es el que acepta 10.0.0.
     Probar `onlyoffice.jwt.transport=HEADER` (`ONLYOFFICE_JWT_TRANSPORT=HEADER`). Ver `OnlyOfficeClient.buildRequest`.
     Documentar en el README, sección "Integración con la API de ONLYOFFICE", qué transporte funcionó.
   - Si la respuesta no es binaria o difiere de lo documentado, investigar y documentar.
2. **Docker Compose + e2e:** `scripts\generate-env.ps1` y luego `scripts\e2e.ps1`. Verificar que la imagen `Dockerfile` se construye y que el contenedor `read_only` arranca bien.
3. **Caché temporal de ONLYOFFICE:** comprobar que se carga `onlyoffice/local-production-linux.json` (retención de 600 s)
   y qué rutas usa `/converter/from-file`:
   `docker compose exec onlyoffice sh -c 'find /var/lib/onlyoffice/documentserver/App_Data/cache/files -type f -mmin -15'`.
   Evaluar la opción `tmpfs` comentada en `docker-compose.yml`. Actualizar el README, sección "Almacenamiento temporal".
4. **Fuentes:** `scripts\check-fonts.ps1 -Docx samples\contrato-ejemplo.docx`. Confirmar si existe `fc-list` en la imagen
   y si las fuentes montadas en `fonts/` aparecen tras reiniciar.
5. **Fidelidad con contratos reales:** exportar a PDF desde Word algunos contratos reales y compararlos con
   `scripts\compare-pdf.ps1`. Calibrar umbrales (`--pixel-threshold`, `--line-tolerance`) y revisar falsos positivos de
   `FONT_SUBSTITUTION_SUSPECTED`. Los contratos reales NO deben subirse al repositorio.
6. Al terminar: actualizar la tabla "Estado de verificación" del README y esta sección.

Mejoras opcionales, no pedidas aún: TLS mediante proxy inverso, endpoint Prometheus, informe de licencias con
`license-maven-plugin`, CI en GitHub Actions (`mvnw verify` sin Docker).
