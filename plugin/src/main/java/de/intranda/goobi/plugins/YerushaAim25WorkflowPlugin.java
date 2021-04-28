package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.goobi.beans.Process;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IWorkflowPlugin;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.filter.ElementFilter;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.ProcessManager;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.http.client.ClientProtocolException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Fileformat;
import ugh.exceptions.UGHException;

/**
 * Plugin for harvesting AIM25 EAD data
 * 
 *
 */
@PluginImplementation
@Log4j2
public class YerushaAim25WorkflowPlugin implements IWorkflowPlugin, IPlugin {

    @Getter
    private String title = "AIM25 Data Import";

    private String url = "https://yerusha.aim25.com/index.php/";

    private String strListIdsVerb = ";oai?verb=ListIdentifiers&metadataPrefix=oai_ead&from=1970-01-01";

    private String strRecordVerb1 = ";oai?verb=GetRecord&identifier=oai:";
    private String strRecordVerb2 = "&metadataPrefix=oai_ead";

    private Boolean boRunCheck = false;

    @Getter
    private String value;

    private ArrayList<String> lstAllIds = new ArrayList<String>();
    private ArrayList<String> lstNewIds = new ArrayList<String>();
    private ArrayList<String> lstJustImported = new ArrayList<String>();

    private EadToMM e2m;

    private String importFolder;

    private XMLConfiguration config;

    @Getter
    @Setter
    private String buttonInfo = "Check AIM25";

    @Getter
    private String infoText = "Check AIM25 for new datasets. This may take some minutes.";

    @Override
    public PluginType getType() {
        return PluginType.Workflow;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_workflow_yerusha_aim25.xhtml";
    }

    /**
     * Constructor
     */
    public YerushaAim25WorkflowPlugin() {

    }

    /**
     * Check AIM25, import any datasets which are nat already imported.
     * 
     * @return
     * @throws Exception
     */
    public String run() throws Exception {

        this.config = ConfigPlugins.getPluginConfig("intranda_workflow_yerusha_aim25");
        int importNumber = config.getInt("importNumber", 0);

        try {
            if (!boRunCheck) {
                //first check AIM25:
                importFolder = ConfigurationHelper.getInstance().getTemporaryFolder();

                this.e2m = new EadToMM(this.config);

                log.info("YerushaAim25 workflow plugin started");

                //this will fill the lstIds with all ids
                checkAim25();

                //remove ids which have already been imported
                checkIdsInWorkflow();

                buttonInfo = "Import missing datasets";

                infoText = infoTextWithNumbers(importNumber);

                boRunCheck = true;

            } else {
                //and import all others 
                importNewIds();

                if (lstNewIds.size() == 0) {

                    infoText = importIdsInfoText();
                    infoText += "<br/> All missing datasets have now been imported.";

                } else {
                    infoText = importIdsInfoText();
                    infoText += infoTextWithNumbers(importNumber);
                }
            }
        } catch (IOException e) {
            log.error(e);
        }

        return "ok";
    }

    private String importIdsInfoText() {

        String text = "The following IDs have been imported: <br/> ";

        for (String id : lstJustImported) {

            text += id + "<br/>";
        }

        return text;
    }

    private String infoTextWithNumbers(int importNumber) {
        String text = "There are a total of " + lstAllIds.size() + " datasets available in AIM25. <br/> Of these, "
                + lstNewIds.size() + " are datasets which have not yet been imported. <br/>  Import them now? ";

        if (importNumber != 0) {

            text += "<br/>  <br/>  A maximum of " + importNumber
                    + " will be imported each time the button is clicked," + " due to a setting in the configuration file.";
        }

        return text;
    }

    /**
     * Go through the list of ids, removing all which have already been imported.
     */
    private void checkIdsInWorkflow() {

        for (String id : lstAllIds) {

            Process existingProcess = ProcessManager.getProcessByTitle(id);
            if (existingProcess == null) {
                lstNewIds.add(id);

            }
        }
    }

    /**
     * For each id in the list lstIds, create a process with the id as its title.
     * 
     * @throws Exception
     */
    private void importNewIds() throws Exception {

        //if this is set in config, only import this number of datasets:
        int importNumber = config.getInt("importNumber", 0);

        lstJustImported = new ArrayList<String>();

        for (String id : lstNewIds) {

            Element element = AIM25Http.getElementFromUrl(url + strRecordVerb1 + id + strRecordVerb2);
            Element ead = element.getDescendants(new ElementFilter("ead")).next();

            if (ead == null) {
                continue;
            }

            try {

                Fileformat fileformat = e2m.getMM(ead);
                if (fileformat == null) {
                    continue;
                }

                //save the file:
                Path rootFolder = Paths.get(importFolder, id);
                Path imagesFolder = Paths.get(rootFolder.toString(), "images");
                StorageProvider.getInstance().createDirectories(rootFolder);
                StorageProvider.getInstance().createDirectories(imagesFolder);
                String fileName = rootFolder.toString() + "/meta.xml";
                fileformat.write(fileName);

                //create the process:
                Process processNew = createProcess(id);

                //move the meta.xml file:
                moveSourceData(rootFolder.toString(), processNew.getId());

                lstJustImported.add(id);

                log.info("New process " + processNew.getId() + " created for AIM25 import " + id);

                if (importNumber != 0 && lstJustImported.size() >= importNumber) {
                    break;
                }
            } catch (IOException | UGHException | JDOMException e) {
                log.error("Error while creating Goobi processes from AIM25", e);
            }
        }

        lstNewIds.removeAll(lstJustImported);

    }

    /**
     * Create a goobi process with the specified title, based on the template specified in config
     * 
     * @param processTitle
     * @return
     * @throws DAOException
     */
    private Process createProcess(String processTitle) throws DAOException {

        Process template = ProcessManager.getProcessByTitle(config.getString("templateTitle"));

        if (template == null) {
            log.error("Could not find template ", config.getString("templateTitle"));
            return null;
        }

        Process processCopy = new Process();
        processCopy.setTitel(processTitle);
        processCopy.setIstTemplate(false);
        processCopy.setInAuswahllisteAnzeigen(false);
        processCopy.setProjekt(template.getProjekt());
        processCopy.setRegelsatz(template.getRegelsatz());
        processCopy.setDocket(template.getDocket());

        // Kopie der Processvorlage anlegen
        BeanHelper bHelper = new BeanHelper();
        bHelper.SchritteKopieren(template, processCopy);
        bHelper.ScanvorlagenKopieren(template, processCopy);
        bHelper.WerkstueckeKopieren(template, processCopy);
        bHelper.EigenschaftenKopieren(template, processCopy);

        //        Processproperty userDefinedA = new Processproperty();
        //        userDefinedA.setTitel("UserDefinedA");
        //        userDefinedA.setWert("Technical_Services");
        //        userDefinedA.setProzess(processCopy);
        //        processCopy.getEigenschaften().add(userDefinedA);

        ProcessManager.saveProcess(processCopy);

        return processCopy;
    }

    /**
     * Move a metadata folder to the correct Goobi Process folder.
     * 
     * @param source
     * @param strProcessTitle
     * @throws IOException
     */
    private void moveSourceData(String source, int id) throws IOException {

        String strMetadataFolder = ConfigurationHelper.getInstance().getMetadataFolder();

        Path destinationRootFolder = Paths.get(strMetadataFolder, String.valueOf(id));

        Path sourceRootFolder = Paths.get(source);

        if (!Files.exists(destinationRootFolder)) {
            StorageProvider.getInstance().createDirectories(destinationRootFolder);
        }

        StorageProvider.getInstance().move(sourceRootFolder, destinationRootFolder);
    }

    /**
     * Collect a list of all ids of datasets from AIM25.
     * 
     * @return
     * @throws IOException
     * @throws ClientProtocolException
     */
    public String checkAim25() throws ClientProtocolException, IOException {

        Element element = AIM25Http.getElementFromUrl(url + strListIdsVerb);

        String strResumeString = getResumeString(element);

        lstAllIds.addAll(getIds(element));

        while (strResumeString != null) {

            Element element2 = AIM25Http.getElementFromUrl(url + strListIdsVerb + strResumeString);
            strResumeString = getResumeString(element2);

            ArrayList<String> lstIdsNew = getIds(element2);
            lstAllIds.addAll(lstIdsNew);

            if (lstIdsNew.size() == 0) {
                break;
            }
        }

        Collections.sort(lstAllIds);

        return "ok";
    }

    /**
     * Extract the aim25 ids from the element
     * 
     * @param element
     * @return
     */
    private ArrayList<String> getIds(Element element) {

        ArrayList<String> lstIdsNew = new ArrayList<String>();
        Element eltList = element.getChild("ListIdentifiers", element.getNamespace());
        List<Element> headers = eltList.getChildren("header", element.getNamespace());

        for (Element head : headers) {
            String strText = head.getChildText("identifier", element.getNamespace());
            String strId = strText.replace("oai:yerusha.aim25.com:", "");
            lstIdsNew.add(strId);
        }

        return lstIdsNew;
    }

    /**
     * Extract the resume string from the element
     * 
     * @param element
     * @return
     */
    private String getResumeString(Element element) {

        Element eltList = element.getChild("ListIdentifiers", element.getNamespace());
        Element res = eltList.getChild("resumptionToken", element.getNamespace());

        if (res != null) {
            String strToken = res.getValue();
            return "&resumptionToken=" + strToken;
        }

        //otherwise
        return null;
    }

    //    //test
    //    public static void main(String[] args) throws Exception {
    //        YerushaAim25WorkflowPlugin yer = new YerushaAim25WorkflowPlugin();
    //
    //        //        yer.run();
    //
    //        yer.lstIds = new ArrayList<String>();
    //        yer.lstIds.add("aim25_4");
    //        yer.e2m = new EadToMM("/opt/digiverso/goobi/config/plugin_intranda_workflow_yerusha_aim25.xml");
    //        yer.importFolder = "/home/joel/git/plugins/ead/import/";
    //
    //        yer.importNewIds();
    //    }
}
