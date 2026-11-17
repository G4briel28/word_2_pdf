package pe.saeta.word2pdf.validation;

import java.io.ByteArrayInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Fábrica StAX endurecida: sin DTD ni entidades externas (protección XXE / billion laughs).
 */
public final class SecureXml {

    private static final XMLInputFactory FACTORY = createFactory();

    private SecureXml() {
    }

    private static XMLInputFactory createFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        return factory;
    }

    public static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        synchronized (FACTORY) {
            return FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
        }
    }
}
