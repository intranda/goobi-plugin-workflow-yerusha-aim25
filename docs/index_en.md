---
title: Data transfer from AIM25
identifier: intranda_workflow_yerusha_aim25
description: Workflow Plugin to check and dowload new EAD datasets from AIM25 and to create Goobi processes
published: true
---

## Introduction
This is a technical documentation for the YerushaAim25 Goobi workflow plugin. It checks AIM25 for new EAD datasets, downloads them and creates Goobi processes for them.

## Installation
In order to use the plugin, the following files must be installed:

```bash
/opt/digiverso/goobi/plugins/workflow/plugin-workflow-yerusha-aim25-base.jar
/opt/digiverso/goobi/plugins/GUI/plugin-workflow-yerusha-aim25-gui.jar
/opt/digiverso/goobi/config/plugin_intranda_workflow_yerusha_aim25.xml
```

To use this plugin, the user must have the correct role authorisation.

Therefore, please assign the role `Plugin_workflow_yerusha_aim25` to the group.

![Correctly assigned role for users](screen1_en.png)


## Overview and functionality
If the plugin has been installed and configured correctly, it can be found under the `Workflow` menu item.

![User interface of the plugin](screen2_en.png)

On the page of the plugin, when the button is clicked, the list of datasets in AIM25 is requested, and for any datasets which have not already been given goobi processes, the datasets are downloaded and converted to goobi processes, based on the template specified in the config file. Here the EAD file is searched using the specified xpath expressions, with corresponding metadata created for the goobi process.


## Configuration
The plugin is configured in the file `plugin_intranda_workflow_yerusha_aim25.xml` as shown here:

{{CONFIG_CONTENT}}

The following table contains a summary of the parameters and their descriptions:

Parameter               | Explanation
------------------------|------------------------------------
|  `templateTitle` | This parameter specifies the title of the workflow template to be used when creating new processes.  |
|  `documenttype` | This parameter defines the goobi DocType for imported documents.  |
|`importFolder`| A folder where data can be temporarily written. |
|`importNumber`   | For testing: if this is not empty or 0, then only this number of datasets will be imported each time the plugin is carried out.   |
|` mapping`  | Xpath mappings from the EAD files to goobi metadata. |
