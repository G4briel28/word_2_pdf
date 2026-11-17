# Licenciamiento

> Este documento es un resumen técnico preparado durante el desarrollo, **no es asesoría legal**.
> Antes de cualquier despliegue comercial o productivo, debe revisarlo un abogado especializado en licencias de software.
> Información verificada en noviembre de 2026; las condiciones de terceros pueden cambiar.

## 1. ONLYOFFICE Docs Community Edition

| Aspecto | Situación |
|---|---|
| Componente | `onlyoffice/documentserver:10.0.0` (imagen Docker oficial, sin modificar) |
| Licencia | GNU Affero General Public License v3 (AGPLv3) **con términos adicionales** de Ascensio System SIA invocando la sección 7 |
| Precio | Gratuito. Gratuito no significa sin obligaciones |
| Límite de conexiones | Las versiones antiguas de Community limitaban a 20 conexiones simultáneas de **edición**. Según informes públicos de mayo de 2026, ese límite se eliminó en 9.4. Este servicio no abre sesiones de edición: solo usa la Conversion API. Aun así, confirme el límite en las notas de la versión que despliegue |

### Términos adicionales (sección 7)

ONLYOFFICE añade al AGPLv3 condiciones sobre la **atribución y la marca**. Según su FAQ de Community y el análisis público de la versión 9.4, en resumen:

- Las versiones modificadas o derivadas deben conservar los avisos de copyright y la atribución a ONLYOFFICE, por ejemplo con una sección "Acerca de".
- La licencia **no concede derechos sobre las marcas ni los logotipos de ONLYOFFICE**, que se rigen por su política de marcas.

Estos términos son objeto de debate público. La FSF y Nextcloud discuten si un logotipo cuenta como "aviso legal razonable" según la sección 7(b). Es un punto a revisar con asesoría legal si alguna vez se modifica o redistribuye ONLYOFFICE.

### Obligaciones relevantes de AGPLv3 para este proyecto

| Escenario | Obligación |
|---|---|
| Usar la imagen oficial **sin modificar**, como servicio interno separado y comunicado por HTTP | Conservar los avisos de licencia y copyright. Si se redistribuye la imagen o el software (por ejemplo, entregándolo a un cliente o incluyéndolo en un instalador), proporcionar el código fuente correspondiente o una oferta para obtenerlo, junto con la licencia |
| **Modificar** ONLYOFFICE (código, parches, imagen derivada con cambios en el software) y permitir que usuarios interactúen con él **a través de una red** | Sección 13 de AGPLv3: ofrecer a esos usuarios el código fuente completo de la versión modificada. Además, cumplir los términos adicionales de atribución |
| Añadir solo fuentes o configuración (volúmenes, `local-production-linux.json`) sin tocar el código | En general no se considera modificar el programa, pero conviene confirmarlo con asesoría legal |

### Riesgos al integrarlo con una aplicación propietaria

1. **Frontera de la "obra derivada".** Este microservicio se comunica con ONLYOFFICE solo por HTTP, en otro proceso y otro contenedor, sin enlazar su código. Esa separación ("arm's length") suele considerarse que **no** extiende la AGPL a la aplicación cliente. No es una garantía jurídica. Evite enlazar, incrustar o copiar código de ONLYOFFICE dentro de aplicaciones propietarias.
2. **Redistribución.** Si ONLYOFFICE se entrega a terceros (on-premise en un cliente, appliances, imágenes propias), se activan las obligaciones de entregar el código fuente y los avisos.
3. **Modificaciones accidentales.** Una imagen "personalizada" con cambios en el software (no solo fuentes o configuración) activa la sección 13 si se ofrece por red.
4. **Marca.** No use el nombre ni el logotipo de ONLYOFFICE para presentar el servicio como propio o avalado por ONLYOFFICE.
5. **Cambios de licencia.** ONLYOFFICE modificó sus términos en 2026. Revise la licencia de cada versión antes de actualizar.
6. **Soporte.** Community no incluye soporte comercial ni SLA. Las ediciones Enterprise o Developer son comerciales y **no** se usan en este proyecto.

### Fuentes incluidas en la imagen de ONLYOFFICE

El Dockerfile oficial instala `ttf-mscorefonts-installer`, aceptando el EULA de las "Core fonts for the Web" de Microsoft (Arial, Times New Roman, Courier New, Verdana, Georgia, etc.). Esto ocurre dentro de la imagen de terceros, no en este repositorio. Las fuentes de Office (Calibri, Cambria, Aptos…) **no** están incluidas y no deben redistribuirse sin licencia. Ver [fonts/README.md](fonts/README.md).

### Qué requiere revisión legal antes del despliegue comercial

- [ ] Confirmar que el uso previsto (servicio interno, sin modificar, sin redistribuir) no genera obligaciones adicionales en su jurisdicción.
- [ ] Evaluar el alcance de los términos adicionales de la sección 7 de ONLYOFFICE.
- [ ] Revisar si se entregará el sistema a clientes (on-premise) y cómo cumplir con la entrega del código fuente.
- [ ] Verificar las licencias de **todas** las fuentes instaladas en `fonts/`.
- [ ] Revisar la política de marcas de ONLYOFFICE si se menciona el producto en la documentación comercial.
- [ ] Evaluar si se necesita soporte comercial (Enterprise) por requisitos de SLA.

## 2. Licencia de este microservicio

El código de `word2pdf` es propiedad de su autor u organización y no incorpora código de ONLYOFFICE. No se ha asignado una licencia de distribución: defínala antes de publicarlo.

## 3. Dependencias Java

| Dependencia | Versión | Licencia | Ámbito |
|---|---|---|---|
| Spring Boot (webmvc, actuator, validation) | 4.1.2 | Apache License 2.0 | Ejecución |
| Spring Framework | (gestionada por Boot) | Apache License 2.0 | Ejecución |
| Apache Tomcat (embebido) | 11.0.x | Apache License 2.0 | Ejecución |
| Jackson | 3.1.x | Apache License 2.0 | Ejecución |
| Micrometer | (gestionada por Boot) | Apache License 2.0 | Ejecución |
| Hibernate Validator | (gestionada por Boot) | Apache License 2.0 | Ejecución |
| SLF4J | (gestionada por Boot) | MIT | Ejecución |
| Logback | (gestionada por Boot) | EPL 1.0 / LGPL 2.1 (doble licencia) | Ejecución |
| Apache PDFBox | 3.0.8 | Apache License 2.0 | Ejecución |
| Nimbus JOSE + JWT | 10.10 | Apache License 2.0 | Ejecución |
| JUnit Jupiter | 6.0.x | Eclipse Public License 2.0 | Solo pruebas |
| Mockito | 5.23.x | MIT | Solo pruebas |
| AssertJ | (gestionada por Boot) | Apache License 2.0 | Solo pruebas |
| Testcontainers | 2.0.x | MIT | Solo pruebas |

Imágenes base Docker: `eclipse-temurin:21-jdk-noble` / `21-jre-noble`. Eclipse Temurin se distribuye bajo GPLv2 con Classpath Exception, y Ubuntu está compuesto por paquetes con licencias libres diversas.

No se usan componentes comerciales (ni Aspose ni Microsoft Office) ni LibreOffice.

Para obtener un inventario exacto de las licencias del árbol de dependencias:

```bash
./mvnw -B org.codehaus.mojo:license-maven-plugin:aggregate-third-party-report
# Informe en target/site/aggregate-third-party-report.html
```

## Referencias

- Conversion API (from-file): https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/request-from-file
- FAQ Community de ONLYOFFICE: https://helpcenter.onlyoffice.com/docs/faq/docs-community.aspx
- ONLYOFFICE 9.4 y cambios de licencia (prensa): https://www.it-connect.tech/onlyoffice-docs-9-4-drops-the-20-connection-limit-and-updates-its-open-source-license/
- Debate sobre la sección 7: https://nextcloud.com/blog/euro-office-license-compliance-and-what-open-source-means/
- Texto de AGPLv3: https://www.gnu.org/licenses/agpl-3.0.html
