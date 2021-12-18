package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.apache.oro.text.perl.Perl5Util;
import org.goobi.beans.Process;
import org.goobi.production.enums.PluginType;
import org.jdom2.Attribute;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.xpath.XPathFactory;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
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
 * Convert EAD data to MetsMods
 *
 */
@Log4j2
public class EadToMM {

    /**
     * Contructor
     * 
     * @param config
     * @throws PreferencesException
     */
    public EadToMM(XMLConfiguration config) throws PreferencesException {

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

    /**
     * Convert an element containgin an EAD configuration into a MetsMods file
     * 
     * @param element
     * @return
     * @throws Exception
     */
    public Fileformat getMM(Element element, String id, String collection) throws Exception {

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
        
        //CatalogueIDDigital to add
        MetadataType mtcid = prefs.getMetadataTypeByName("CatalogIDDigital");
        Metadata mcid = new Metadata(mtcid);
        mcid.setValue(id);
        volume.addMetadata(mcid);
        
        //Collection to add
        MetadataType mtdc = prefs.getMetadataTypeByName("singleDigCollection");
        Metadata mdc = new Metadata(mtdc);
        mdc.setValue(collection);
        volume.addMetadata(mdc);
        
        DocStruct anchor = null;
        if (anchorType != null) {
            anchor = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(anchorType));
            anchor.addChild(volume);
            digitalDocument.setLogicalDocStruct(anchor);
            anchor.addMetadata(mcid);
            anchor.addMetadata(mdc);
        } else {
            digitalDocument.setLogicalDocStruct(volume);
        }
        DocStruct physical = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
        digitalDocument.setPhysicalDocStruct(physical);

        // add images
        Metadata newmd = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
        newmd.setValue("/images/");
        physical.addMetadata(newmd);
        
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

                                value = correctValue(value, sp.getMetadataName());
                                
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


    /**
     * For meta.originalAccessLocations, change _Hyde Park_ _Westminster_ _London_ _England_ 
     * to look like Hyde Park, Westminster, London, England
     * @param value2
     * @param metaName
     * @return
     */
    private String correctValue(String valueOrig, String metaName) {
       
        if (!metaName.contentEquals("originalAccessLocations") || 
                valueOrig.charAt(0) != '_'  || 
                valueOrig.charAt(valueOrig.length()-1) != '_') {
            return valueOrig;
        }
        
        String strNew = valueOrig.replaceAll("_ _", ", ");
        strNew = strNew.substring(1, strNew.length()-1);
        
        return strNew;
    }

    /**
     * Adjust the metadata:  join address data into one metadata, and extract start and end dates from DateOfOrigin.
     * 
     * @param mm
     * @throws PreferencesException
     * @throws MetadataTypeNotAllowedException
     */
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

    /**
     * config
     * @param config
     * @throws PreferencesException
     */
    private void loadConfiguration(XMLConfiguration config) throws PreferencesException {

        namespaces = new ArrayList<>();
        Namespace namespace = Namespace.getNamespace("ead", "http://www.openarchives.org/OAI/2.0/");
        namespaces.add(namespace);

        metadataList = new ArrayList<>();
        this.prefs = new Prefs();

        Process template = ProcessManager.getProcessByTitle(config.getString("templateTitle"));

        if (template == null) {
            log.error("Could not find template " + config.getString("templateTitle"));
            throw new PreferencesException("Could not find template " + config.getString("templateTitle"));
        }

        String strRuleset = config.getString("ruleset");
        try {
            prefs.loadPrefs(ConfigurationHelper.getInstance().getRulesetFolder() + template.getRegelsatz().getDatei());
        } catch (PreferencesException e) {
            log.error("Error while reading the configuration file " + strRuleset, e);
        }

        config.setExpressionEngine(new XPathExpressionEngine());
        //        List<HierarchicalConfiguration> fields = config.configurationsAt("/namespaces/namespace");
        //        for (HierarchicalConfiguration sub : fields) {
        //            Namespace namespace = Namespace.getNamespace(sub.getString("@prefix"), sub.getString("@uri"));
        //            namespaces.add(namespace);
        //        }

        documentType = config.getString("/documenttype[@isanchor='false']");
        anchorType = config.getString("/documenttype[@isanchor='true']", null);

        List<HierarchicalConfiguration> fields = config.configurationsAt("mapping/metadata");
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
}
