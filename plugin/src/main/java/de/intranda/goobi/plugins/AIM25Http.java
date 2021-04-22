package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import lombok.extern.log4j.Log4j2;

/**
 * Class for querying AIM25 
 * 
 * @author joel
 *
 */
@Log4j2
public class AIM25Http {

    public AIM25Http() {
    }

    public static Element getElementFromUrl(String url) throws ClientProtocolException, IOException {
        String response = getStringFromUrl(url);
        return getRecordFromResponse(response);
    }

    private static String getStringFromUrl(String url) throws ClientProtocolException, IOException {
        String response = "";
        CloseableHttpClient client = null;
        HttpGet request = new HttpGet(url);

        Header header = new BasicHeader("X-OAI-API-Key", "95e8ce05586b7dda");
        List<Header> headers = Collections.singletonList(header);
        client = HttpClients.custom().setDefaultHeaders(headers).build();
        response = client.execute(request, stringResponseHandler);

        return response;
    }

    private static ResponseHandler<String> stringResponseHandler = new ResponseHandler<String>() {
        @Override
        public String handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                log.error("Wrong status code : " + response.getStatusLine().getStatusCode());
                return null;
            }
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                return EntityUtils.toString(entity, "utf-8");
            } else {
                return null;
            }
        }
    };

    private static Element getRecordFromResponse(String response) {

        SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
        builder.setFeature("http://xml.org/sax/features/validation", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            Document doc = builder.build(new StringReader(response), "utf-8");
            Element oaiRootElement = doc.getRootElement();

            return oaiRootElement;
        } catch (JDOMException | IOException e) {
            log.error(e);
        }
        return null;
    }

}
