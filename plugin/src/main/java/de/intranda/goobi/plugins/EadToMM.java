package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.apache.oro.text.perl.Perl5Util;
import org.goobi.production.enums.PluginType;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.jdom2.xpath.XPathFactory;

import lombok.Getter;
import lombok.extern.log4j.Log4j;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.fileformats.mets.MetsMods;

/**
 * Covert EAD data to MetsMods
 *
 */
@Log4j
public class EadToMM {

//    //for testing
//    public static void main(String[] args) {
//
//        String strConfig = "/home/joel/git/plugins/ead/ead-to-mm.xml";
//        String strEad = "/home/joel/git/plugins/ead/aim25_4.xml";
//
//        EadToMM em = new EadToMM(strConfig);
//
//        try {
//            Element element = em.getRecord(strEad);
//            Fileformat ff = em.getMM(element);
//
//            ff.write("/home/joel/git/plugins/ead/mm-out.xml");
//
//        } catch (Exception e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        }
//    }
//
//    //Ctor: takes path to config file
//    public EadToMM(String strConfig) {
//
//        loadConfiguration(strConfig);
//    }

    /**
     * Contructor
     * 
     * @param config
     */
    public EadToMM(XMLConfiguration config) {

        loadConfiguration(config);
    }

    private List<Namespace> namespaces = null;

    private List<ConfigurationEntry> metadataList = null;

    private String documentType = null;
    private String anchorType = null;

    private XPathFactory xFactory = XPathFactory.instance();

    @Getter
    private PluginType type = PluginType.Opac;

    @Getter
    private Perl5Util perlUtil = new Perl5Util();

    private Prefs prefs;
    
    

    public Fileformat getMM(Element element) throws Exception {

        if (element == null) {
            return null;
        }

        Fileformat mm = new MetsMods(prefs);
        DigitalDocument digitalDocument = new DigitalDocument();
        mm.setDigitalDocument(digitalDocument);

        DocStruct volume = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(documentType));

        if (volume.getType() == null) {
            log.error("Cannot initialize document type " + documentType);
            return null;
        }

        DocStruct anchor = null;
        if (anchorType != null) {
            anchor = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(anchorType));
            anchor.addChild(volume);
            digitalDocument.setLogicalDocStruct(anchor);
        } else {
            digitalDocument.setLogicalDocStruct(volume);
        }
        DocStruct physical = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
        digitalDocument.setPhysicalDocStruct(physical);

        for (ConfigurationEntry sp : metadataList) {
            List<String> metadataValues = new ArrayList<>();
            if ("Element".equalsIgnoreCase(sp.getXpathType())) {
                List<Element> data = xFactory.compile(sp.getXpath(), Filters.element(), null, namespaces).evaluate(element);
                for (Element e : data) {
                    String value = e.getValue();
                    metadataValues.add(value);
                }
            } else if ("Attribute".equalsIgnoreCase(sp.getXpathType())) {
                List<Attribute> data = xFactory.compile(sp.getXpath(), Filters.attribute(), null, namespaces).evaluate(element);
                for (Attribute a : data) {
                    String value = a.getValue();
                    metadataValues.add(value);
                }

            } else {
                List<String> data = xFactory.compile(sp.getXpath(), Filters.fstring(), null, namespaces).evaluate(element);
                for (String value : data) {
                    metadataValues.add(value);
                }
            }

            MetadataType mdt = prefs.getMetadataTypeByName(sp.getMetadataName());
            if (mdt == null) {
                log.error("Cannot initialize metadata type " + sp.getMetadataName());
            } else {

                for (String value : metadataValues) {
                    if (StringUtils.isNotBlank(sp.getRegularExpression())) {
                        value = perlUtil.substitute(sp.getRegularExpression(), value);
                    }
                    if (StringUtils.isNotBlank(sp.getSearch()) && StringUtils.isNotBlank(sp.getReplace())) {
                        value = value.replace(sp.getSearch(), sp.getReplace().replace("\\u0020", " "));
                    }
                    if (StringUtils.isNotBlank(value)) {
                        try {
                            if (mdt.getIsPerson()) {
                                Person p = new Person(mdt);
                                if (value.contains(",")) {
                                    p.setLastname(value.substring(0, value.indexOf(",")).trim());
                                    p.setFirstname(value.substring(value.indexOf(",") + 1).trim());
                                } else {
                                    p.setLastname(value);
                                }
                                if ("physical".equals(sp.getLevel())) {
                                    // add it to phys
                                    physical.addPerson(p);
                                } else if ("topstruct".equals(sp.getLevel())) {
                                    // add it to topstruct
                                    volume.addPerson(p);
                                } else if ("anchor".equals(sp.getLevel()) && anchor != null) {
                                    // add it to anchor
                                    anchor.addPerson(p);
                                }
                            } else {

                                Metadata md = new Metadata(mdt);
                                md.setValue(value);
                                if ("physical".equals(sp.getLevel())) {
                                    // add it to phys
                                    physical.addMetadata(md);
                                } else if ("topstruct".equals(sp.getLevel())) {
                                    // add it to topstruct
                                    volume.addMetadata(md);
                                } else if ("anchor".equals(sp.getLevel()) && anchor != null) {
                                    // add it to anchor
                                    anchor.addMetadata(md);
                                }
                            }
                        } catch (Exception e) {
                            log.error(e);
                        }

                    }
                }
            }
        }

        adjustMetadata(mm);

        return mm;

    }

//    private Element getRecord(String strEadFile) {
//
//        SAXBuilder builder = new SAXBuilder();
//        File xmlFile = new File(strEadFile);
//
//        try {
//            Document document = (Document) builder.build(xmlFile);
//            Element rootNode = document.getRootElement();
//
//            return rootNode;
//        }
//
//        catch (JDOMException | IOException e) {
//            log.error(e);
//        }
//        return null;
//    }

    //Adjust the metadata
    private void adjustMetadata(Fileformat mm) throws PreferencesException, MetadataTypeNotAllowedException {

        DocStruct log = mm.getDigitalDocument().getLogicalDocStruct();

        //Address all in one md:
        MetadataType mdtAdd = prefs.getMetadataTypeByName("ContactPostal");
        List<Metadata> lstMdAdd = (List<Metadata>) log.getAllMetadataByType(mdtAdd);

        if (lstMdAdd != null) {
            for (int i = 1; i < lstMdAdd.size(); i++) {

                String val = lstMdAdd.get(0).getValue();
                lstMdAdd.get(0).setValue(val + System.lineSeparator() + lstMdAdd.get(i).getValue());
                log.removeMetadata(lstMdAdd.get(i));
            }
        }

        //Date of origin:
        MetadataType mdtOrig = prefs.getMetadataTypeByName("DateOfOrigin");
        List<Metadata> lstMdOrig = (List<Metadata>) log.getAllMetadataByType(mdtOrig);

        if (lstMdOrig != null && lstMdOrig.size() > 0) {
            String strDate = lstMdOrig.get(0).getValue();
            if (strDate.split("/").length > 1) {
                String strStart = strDate.split("/")[0];
                String strEnd = strDate.split("/")[1];
                MetadataType mdtStart = prefs.getMetadataTypeByName("DateStart");
                Metadata mdS = new Metadata(mdtStart);
                mdS.setValue(strStart);
                log.addMetadata(mdS);

                MetadataType mdtEnd = prefs.getMetadataTypeByName("DateEnd");
                Metadata mdE = new Metadata(mdtEnd);
                mdE.setValue(strEnd);
                log.addMetadata(mdE);
            }
        }
    }

    
    
    
//    private void loadConfiguration(String strConfig) {
//
//        XMLConfiguration config1 = new XMLConfiguration();
//        config1.setDelimiterParsingDisabled(true);
//        try {
//            config1.load(strConfig);
//        } catch (ConfigurationException e) {
//            log.error("Error while reading the configuration file " + strConfig, e);
//        }
//        
//        loadConfiguration(config1);
//    }

    private void loadConfiguration(XMLConfiguration config) {
      
        namespaces = new ArrayList<>();
        metadataList = new ArrayList<>();
        this.prefs = new Prefs();
        String strRuleset = config.getString("ruleset");
        try {
            prefs.loadPrefs(strRuleset);
        } catch (PreferencesException e) {
            log.error("Error while reading the configuration file " + strRuleset, e);
        }

        config.setExpressionEngine(new XPathExpressionEngine());
        List<HierarchicalConfiguration> fields = config.configurationsAt("/namespaces/namespace");
        for (HierarchicalConfiguration sub : fields) {
            Namespace namespace = Namespace.getNamespace(sub.getString("@prefix"), sub.getString("@uri"));
            namespaces.add(namespace);
        }

        documentType = config.getString("/documenttype[@isanchor='false']");
        anchorType = config.getString("/documenttype[@isanchor='true']", null);

        fields = config.configurationsAt("mapping/metadata");
        for (HierarchicalConfiguration sub : fields) {
            String metadataName = sub.getString("@name");
            String xpathValue = sub.getString("@xpath");
            String level = sub.getString("@level", "topstruct");
            String xpathType = sub.getString("@xpathType", "Element");

            String regularExpression = sub.getString("@regularExpression");
            String search = sub.getString("@search");
            String replace = sub.getString("@replace");

            ConfigurationEntry entry = new ConfigurationEntry();
            entry.setLevel(level);
            entry.setMetadataName(metadataName);
            entry.setXpath(xpathValue);
            entry.setXpathType(xpathType);
            entry.setRegularExpression(regularExpression);
            entry.setSearch(search);
            entry.setReplace(replace);

            metadataList.add(entry);
        }
    }

}
