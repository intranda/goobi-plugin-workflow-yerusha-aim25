package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.goobi.beans.LogEntry;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IWorkflowPlugin;
import org.jdom2.Element;
import org.jdom2.JDOMException;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.http.client.ClientProtocolException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Fileformat;
import ugh.exceptions.UGHException;

@PluginImplementation
@Log4j2
public class YerushaAim25WorkflowPlugin implements IWorkflowPlugin, IPlugin {

    //test
    public static void main(String[] args) throws Exception {
        YerushaAim25WorkflowPlugin yer = new YerushaAim25WorkflowPlugin();

        yer.run();
    }

    @Getter
    private String title = "intranda_workflow_yerusha_aim25";

    private String url = "https://yerusha.aim25.com/index.php/";

    private String strListIdsVerb = ";oai?verb=ListIdentifiers&metadataPrefix=oai_ead&from=1970-01-01";

    private String strRecordVerb1 = ";oai?verb=GetRecord&identifier=oai:";
    private String strRecordVerb2 = "&metadataPrefix=oai_ead";

    @Getter
    private String value;

    private ArrayList<String> lstIds = new ArrayList<String>();

    private EadToMM e2m;

    private String projectName;

    private String importFolder;

    private XMLConfiguration config;

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

    public void run() throws Exception {

        this.config = ConfigPlugins.getPluginConfig(this.title);

        importFolder = config.getString("importFolder");

        projectName = config.getString("project");

        this.e2m = new EadToMM(this.config);

        log.info("YerushaAim25 workflow plugin started");
        //        value = ConfigPlugins.getPluginConfig(title).getString("value", "default value");

        try {
            //this will fill the lstIds with all ids
            checkAim25();

            //remove ids which have already been imported
            checkIdsInWorkflow();

            //and import all others 
            importNewIds();

        } catch (IOException e) {
            log.error(e);
        }
    }

    //Remove all ids already imported.
    private void checkIdsInWorkflow() {

        ArrayList<String> lstNew = new ArrayList<String>();

        for (String id : lstIds) {

            Process existingProcess = ProcessManager.getProcessByTitle(id);
            if (existingProcess != null) {
                lstNew.add(id);

                break;
            }
        }

        lstIds = lstNew;
    }

    private void importNewIds() throws Exception {

        for (String id : lstIds) {

            Element element = AIM25Http.getElementFromUrl(url + strRecordVerb1 + id + strRecordVerb2);
            Element ead = element.getChild("ead", element.getNamespace());

            try {

                Fileformat fileformat = e2m.getMM(ead.getValue());
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
                Process processNew = getProcess(id);
                ProcessManager.saveProcess(processNew);

                moveSourceData(rootFolder.toString(), processNew.getTitel());

            } catch (IOException | UGHException | JDOMException e) {
                log.error("Error while creating Goobi processes from AIM25", e);
            }

        }

    }

    /**
     * Move any folder containing images to the correct Goobi Process folder.
     * 
     * @param source
     * @param strProcessTitle
     * @throws IOException
     */
    private void moveSourceData(String source, String strProcessTitle) throws IOException {
        Path destinationRootFolder = Paths.get(importFolder, strProcessTitle);
        Path destinationImagesFolder = Paths.get(destinationRootFolder.toString(), "images");

        Path sourceRootFolder = Paths.get(source);
        Path sourceImageFolder = Paths.get(sourceRootFolder.toString(), "images");

        if (!Files.exists(destinationRootFolder)) {
            try {
                StorageProvider.getInstance().createDirectories(destinationRootFolder);
            } catch (IOException e) {
                log.error(e);
            }
        }

        // images
        if (Files.exists(sourceImageFolder)) {
            if (!Files.exists(destinationImagesFolder)) {
                try {
                    StorageProvider.getInstance().createDirectories(destinationImagesFolder);
                    //                    Files.createDirectories(destinationImagesFolder);
                } catch (IOException e) {
                    log.error(e);
                }
            }
            //            List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceImageFolder.toString());
            //
            //            for (Path currentData : dataInSourceImageFolder) {
            //                if (Files.isDirectory(currentData)) {
            //                    try {
            //                        moveFolder(currentData, destinationImagesFolder);
            //                    } catch (IOException e) {
            //                        log.error(e);
            //                        throw e;
            //                    }
            //                } else {
            //                    try {
            //                        moveFile(currentData, Paths.get(destinationImagesFolder.toString(), currentData.getFileName().toString()));
            //                    } catch (IOException e) {
            //                        log.error(e);
            //                        throw e;
            //                    }
            //                }
            //            }
        }
    }

    /**
     * Move the folder
     * 
     * @param currentData
     * @param destinationFolder
     * @throws IOException
     */
    private void moveFolder(Path currentData, Path destinationFolder) throws IOException {
        Path destinationSubFolder;

        String foldername = currentData.getFileName().toString();
        destinationSubFolder = Paths.get(destinationFolder.toString(), foldername);

        if (!Files.exists(destinationSubFolder)) {
            StorageProvider.getInstance().createDirectories(destinationSubFolder);
        }

        StorageProvider.getInstance().move(currentData, destinationSubFolder);

    }

    private Process getProcess(String id) throws DAOException {

        Process process = new Process();
        Project newProject = ProjectManager.getProjectByName(projectName);

        process.setProjekt(newProject);
        process.setProjectId(newProject.getId());
        process.setTitel(id);

        LogEntry entry = new LogEntry();
        entry.setContent("Automatic process creation after AIM25 injest");
        entry.setCreationDate(new Date());
        entry.setProcessId(process.getId());
        entry.setType(LogType.DEBUG);
        entry.setUserName("-");
        process.getProcessLog().add(entry);

        ProcessManager.saveProcessInformation(process);

        return process;
    }

    /**
     * rec.getId()
     * 
     * @return
     * @throws IOException
     * @throws ClientProtocolException
     */
    public String checkAim25() throws ClientProtocolException, IOException {

        Element element = AIM25Http.getElementFromUrl(url + strListIdsVerb);

        String strResumeString = getResumeString(element);

        lstIds.addAll(getIds(element));

        while (strResumeString != null) {

            Element element2 = AIM25Http.getElementFromUrl(url + strListIdsVerb + strResumeString);
            strResumeString = getResumeString(element2);

            ArrayList<String> lstIdsNew = getIds(element2);
            lstIds.addAll(lstIdsNew);

            if (lstIdsNew.size() == 0) {
                break;
            }
        }

        Collections.sort(lstIds);

        return "ok";
    }

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

}
