package org.renci.mobius.controllers;


import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.renci.comet.CometDataManager;
import org.renci.mobius.controllers.chameleon.ChameleonContext;
import org.renci.mobius.controllers.chameleon.StackContext;
import org.renci.mobius.controllers.exogeni.ExogeniContext;
import org.renci.mobius.controllers.exogeni.ExogeniFlavorAlgo;
import org.renci.mobius.entity.WorkflowEntity;
import org.renci.mobius.model.*;
import org.renci.mobius.notification.NotificationPublisher;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
 * @brief class represents a worflow and maintains hashmap of cloud context per site
 *
 * @author kthare10
 */
class Workflow {
    private String workflowID;
    protected WorkflowOperationLock lock;
    private HashMap<String, CloudContext> siteToContextHashMap;
    private HashMap<String, String> hostNameToComputeRequestMap;
    private HashMap<String, String> networkRequestToComputeRequestMap;
    private int nodeCount, storageCount, stitchCount;
    private FutureRequests futureRequests;
    private static final Logger LOGGER = LogManager.getLogger( Workflow.class.getName() );

    /*
     * @brief constructor
     *
     * @param id - workflow id
     */
    Workflow(String id) {
        workflowID = id;
        lock = new WorkflowOperationLock();
        siteToContextHashMap = new HashMap<>();
        hostNameToComputeRequestMap = new HashMap<>();
        networkRequestToComputeRequestMap = new HashMap<>();
        nodeCount = 0;
        storageCount = 0;
        stitchCount = 0;
        futureRequests = new FutureRequests();
    }

    /*
     * @brief constructor
     *
     * @param workflow - workflow entity read from database
     */
    Workflow(WorkflowEntity workflow) {
        workflowID = workflow.getWorkflowId();
        LOGGER.debug("workflowID=" + workflowID);
        nodeCount = workflow.getNodeCount();
        LOGGER.debug("nodeCount=" + nodeCount);
        storageCount = workflow.getStorageCount();
        LOGGER.debug("storageCount=" + storageCount);
        stitchCount = workflow.getStitchCount();
        LOGGER.debug("stitchCount=" + stitchCount);
        lock = new WorkflowOperationLock();
        siteToContextHashMap = new HashMap<String, CloudContext>();
        futureRequests = new FutureRequests();
        hostNameToComputeRequestMap = new HashMap<>();
        networkRequestToComputeRequestMap = new HashMap<>();

        // process json to construct siteToContextHashMap
        if(workflow.getSiteContextJson() != null) {
            LOGGER.debug("SiteContext =" + workflow.getSiteContextJson());
            JSONArray array = (JSONArray) JSONValue.parse(workflow.getSiteContextJson());
            if(array != null) {
                for (Object object : array) {
                    try {
                        JSONObject c = (JSONObject) object;
                        String site = (String) c.get("site");
                        LOGGER.debug("site=" + site);
                        CloudContext context = CloudContextFactory.getInstance().createCloudContext(site, workflow.getWorkflowId());
                        JSONArray sliceArray = (JSONArray) c.get("slices");
                        context.fromJson(sliceArray);
                        context.loadCloudSpecificDataFromJson(c);
                        siteToContextHashMap.put(site, context);
                    } catch (Exception e) {
                        LOGGER.error("Exception occured while loading context from database e= " + e);
                        e.printStackTrace();
                    }
                }
            }
            else {
                LOGGER.error("JSON parsing failed");
            }
        }
        if(workflow.getHostNamesJson() != null) {
            LOGGER.debug("HostNameContexts =" + workflow.getHostNamesJson());
            JSONObject object = (JSONObject) JSONValue.parse(workflow.getHostNamesJson());
            if(object != null) {
                Set<String> keySet = object.keySet();
                for (String key : keySet) {
                    try {
                        LOGGER.debug("hostname=" + key);
                        String requestString = (String) object.get(key);
                        LOGGER.debug("requestString=" + requestString);
                        hostNameToComputeRequestMap.put(key, requestString);
                    } catch (Exception e) {
                        LOGGER.error("Exception occured while loading context from database e= " + e);
                        e.printStackTrace();
                    }
                }
            }
            else {
                LOGGER.error("JSON parsing failed");
            }
        }
        if(workflow.getNetworkRequestsJson() != null) {
            LOGGER.debug("NetworkRequestJson =" + workflow.getNetworkRequestsJson());
            JSONObject object = (JSONObject) JSONValue.parse(workflow.getNetworkRequestsJson());
            if(object != null) {
                Set<String> keySet = object.keySet();
                for (String key : keySet) {
                    try {
                        LOGGER.debug("source+dest=" + key);
                        String requestString = (String) object.get(key);
                        LOGGER.debug("requestString=" + requestString);
                        networkRequestToComputeRequestMap.put(key, requestString);
                    } catch (Exception e) {
                        LOGGER.error("Exception occured while loading context from database e= " + e);
                        e.printStackTrace();
                    }
                }
            }
            else {
                LOGGER.error("JSON parsing failed");
            }
        }
    }

    /*
     * @brief returns workflow id
     *
     * @return worlflow id
     */
    public String getWorkflowID() {
        return workflowID;
    }

    /*
     * @brief converts workflow object into workflow entity to be written to database
     *
     * @return workflow entity
     */
    public WorkflowEntity convert() {
        String siteJson = null;
        String hostJson = null;
        String networkRequestJson = null;

        WorkflowEntity retVal = null;

        if(siteToContextHashMap != null && siteToContextHashMap.size() != 0) {
            JSONArray array = new JSONArray();
            CloudContext context = null;
            for (HashMap.Entry<String, CloudContext> e : siteToContextHashMap.entrySet()) {
                context = e.getValue();
                JSONObject c = new JSONObject();
                c.put("type", context.getCloudType().toString());
                c.put("site", context.getSite());
                JSONArray slices = context.toJson();
                if(slices != null) {
                    c.put("slices", slices);
                }
                c = context.addCloudSpecificDataToJson(c);
                array.add(c);
            }
            siteJson = array.toJSONString();
            LOGGER.debug("siteJson=" + siteJson);
        }
        if(hostNameToComputeRequestMap != null && hostNameToComputeRequestMap.size() != 0) {
            JSONObject object = new JSONObject();
            for (HashMap.Entry<String, String> e : hostNameToComputeRequestMap.entrySet()) {
                if(e.getValue() != null) {
                    object.put(e.getKey(), e.getValue());
                }
            }
            hostJson = object.toJSONString();
            LOGGER.debug("hostJson=" + hostJson);
        }

        if(networkRequestToComputeRequestMap != null && networkRequestToComputeRequestMap.size() != 0) {
            JSONObject object = new JSONObject();
            for (HashMap.Entry<String, String> e : networkRequestToComputeRequestMap.entrySet()) {
                if(e.getValue() != null) {
                    object.put(e.getKey(), e.getValue());
                }
            }
            networkRequestJson = object.toJSONString();
            LOGGER.debug("networkRequestJson=" + networkRequestJson);
        }
        retVal = new WorkflowEntity(this.workflowID, nodeCount, storageCount, stitchCount, siteJson,
                hostJson, networkRequestJson);

        return retVal;
    }

    private String computeRequestToJsonString(ComputeRequest request) {
        String retVal = null;
        if(request != null) {
            JSONObject object = new JSONObject();

            if(request.getSite() != null) {
                object.put("site", request.getSite());
            }
            if(request.getCpus() != null) {
                object.put("cpus", request.getCpus().toString());
            }
            if(request.getGpus() != null) {
                object.put("gpus", request.getGpus().toString());
            }
            if(request.getRamPerCpus()!= null) {
                object.put("ramPerCpus", request.getRamPerCpus().toString());
            }
            if(request.getDiskPerCpus()!= null) {
                object.put("diskPerCpus", request.getDiskPerCpus().toString());
            }
            object.put("coallocate", request.isCoallocate().toString());
            object.put("slicePolicy", request.getSlicePolicy().toString());

            if(request.getSliceName()!= null) {
                object.put("sliceName", request.getSliceName());
            }
            if(request.getHostNamePrefix()!= null) {
                object.put("hostNamePrefix", request.getHostNamePrefix() + "MON");
            }
            if(request.getBandwidth()!= null) {
                object.put("bandwidth", request.getBandwidth());
            }
            object.put("networkType", request.getNetworkType().toString());

            if(request.getPhysicalNetwork()!= null) {
                object.put("physicalNetwork", request.getPhysicalNetwork());
            }
            if(request.getExternalNetwork()!= null) {
                object.put("externalNetwork", request.getExternalNetwork());
            }
            if(request.getNetworkCidr()!= null) {
                object.put("networkCidr", request.getNetworkCidr());
            }
            if(request.getImageUrl()!= null) {
                object.put("imageUrl", request.getImageUrl());
            }
            if(request.getImageHash()!= null) {
                object.put("imageHash", request.getImageHash());
            }
            if(request.getImageName()!= null) {
                object.put("imageName", request.getImageName());
            }
            if(request.getPostBootScript()!= null) {
                object.put("postBootScript", request.getPostBootScript());
            }
            if(request.getForceflavor()!= null) {
                object.put("forceflavor", request.getForceflavor());
            }
            retVal = object.toString();
        }
        return retVal;
    }


    /*
     * @brief function to release all resources associated with this workflow
     */
    public void stop() throws Exception {
        LOGGER.debug("IN");
        CloudContext context = null;
        try {
            for (HashMap.Entry<String, String> networkRequest : networkRequestToComputeRequestMap.entrySet()) {
                LOGGER.debug("Key=" +  networkRequest.getKey());
                LOGGER.debug("Value=" +  networkRequest.getValue());
                JSONObject object = (JSONObject) JSONValue.parse(networkRequest.getValue());
                LOGGER.debug("JSON=" + object);
                LOGGER.debug("JSON=" + object.toString());

                NetworkRequest request = new NetworkRequest();
                request.setAction(NetworkRequest.ActionEnum.DELETE);
                request.setSource((String) object.get("source"));
                request.setSourceIP((String) object.get("sourceIP"));
                request.setSourceSubnet((String) object.get("sourceSubnet"));
                if (object.containsKey("sourceLocalSubnet")) {
                    request.setSourceLocalSubnet((String) object.get("sourceLocalSubnet"));
                }
                request.setDestination((String) object.get("destination"));
                request.setDestinationIP((String) object.get("destinationIP"));
                request.setDestinationIP((String) object.get("destinationSubnet"));
                if (object.containsKey("destLocalSubnet")) {
                    request.setDestLocalSubnet((String) object.get("destLocalSubnet"));
                }
                request.setLinkSpeed((String) object.get("linkSpeed"));
                if (object.containsKey("chameleonSdxControllerIP")) {
                    request.setChameleonSdxControllerIP((String) object.get("chameleonSdxControllerIP"));
                }
                processNetworkRequest(request, false);

            }
        }
        catch (Exception e) {
            LOGGER.debug("Exception occured while doing network cleanup");
            LOGGER.debug(e);
            e.printStackTrace();
        }
        for(HashMap.Entry<String, CloudContext> e : siteToContextHashMap.entrySet()) {
            context = e.getValue();
            context.stop();
        }
        siteToContextHashMap.clear();
        LOGGER.debug("OUT");
    }

    /*
     * @brief function to check get status for the workflow
     *
     * @return string representing status
     */
    public String status() throws Exception {
        LOGGER.debug("IN");

        JSONArray array = new JSONArray();

        CloudContext context = null;
        for(HashMap.Entry<String, CloudContext> e : siteToContextHashMap.entrySet()) {
            context = e.getValue();
            JSONObject result = context.getStatus();
            if(result != null && !result.isEmpty()) {
                array.add(result);
            }
        }
        LOGGER.debug("OUT");
        return array.toString();
    }

    /*
     * @brief acquire mutex lock
     *
     * @throws InterruptedException
     */
    public void lock() throws InterruptedException {
        lock.acquire();
    }

    /*
     * @brief release mutex lock
     *
     */
    public void unlock() {
        lock.release();
    }

    /*
     * @brief true of mutex is lock; false otherwise
     *
     * @return true of mutex is lock; false otherwise
     */
    public boolean locked() {
        return (lock.availablePermits() == 0);
    }

    /*
     * @brief function to add HostNames and respective ComputeRequest to hostNameToRequest hashmap
     *        which is used later on by monitoring if thresholds are crossed to reprovision instances
     *
     * @param response - compute response
     * @param request - compute request
     *
     * @return None
     */
    private void addToHostNameMap(ComputeResponse response, ComputeRequest request) {
        if(response.getHostNames() != null) {
            for (Map.Entry<String, String> host: response.getHostNames().entrySet()) {
                if(host.getValue() != null) {
                    request.setSlicePolicy(ComputeRequest.SlicePolicyEnum.EXISTING);
                    request.setSliceName(host.getValue());
                }
                hostNameToComputeRequestMap.put(host.getKey(), computeRequestToJsonString(request));
            }
        }
    }

    /*
     * @brief function to process compute request
     *
     * @param request - compute request
     * @param isFutureRequest - true in case this is a future request; false otherwise
     *
     * @throws Exception in case of error
     *
     */
    public ComputeResponse processComputeRequest(ComputeRequest request, boolean isFutureRequest) throws Exception{
        LOGGER.debug("IN request=" + request + " isFutureRequest=" + isFutureRequest);
        ComputeResponse computeResponse = null;
        CloudContext context = null;
        boolean addContextToMap = false;
        try {
            if(request.getSlicePolicy() == ComputeRequest.SlicePolicyEnum.EXISTING && request.getSliceName() == null) {
                throw new MobiusException(HttpStatus.BAD_REQUEST, "Slice name must be specified for SlicePolicy-exisiting");
            }

            if(request.getSlicePolicy() == ComputeRequest.SlicePolicyEnum.EXISTING) {
                // Look up existing slice
                for(CloudContext c : siteToContextHashMap.values()) {
                    if(c.containsSlice(request.getSliceName())) {
                        context = c;
                        break;
                    }
                }
                if(context == null) {
                    throw new MobiusException(HttpStatus.NOT_FOUND, "Slice not found for SlicePolicy-exisiting");
                }
            }
            else {
                // Lookup an existing stack
                context = siteToContextHashMap.get(request.getSite());
                // Create a new slice if not found
                if (context == null) {
                    context = CloudContextFactory.getInstance().createCloudContext(request.getSite(), workflowID);
                    addContextToMap = true;
                }
            }

            computeResponse = context.processCompute(request, nodeCount, stitchCount, isFutureRequest);
            nodeCount = computeResponse.getNodeCount();
            stitchCount = computeResponse.getStitchCount();
            LOGGER.debug("nodeCount = " + nodeCount);
            if (addContextToMap) {
                siteToContextHashMap.put(request.getSite(), context);
            }
            addToHostNameMap(computeResponse, request);
            if (context instanceof ChameleonContext &&
                    request.getStitchPortUrl() != null &&
                    request.getStitchTag() != null) {

                ChameleonContext chameleonContext = (ChameleonContext) context;
                ExogeniContext stitchContext = (ExogeniContext) CloudContextFactory.getInstance().createCloudContext(CloudContext.CloudType.Exogeni.toString(), workflowID);

                String destinationUrl = null;

                if (context.getSite().contains(StackContext.RegionUC)) {
                    destinationUrl = MobiusConfig.getInstance().getChameleonUCStitchPort();
                }
                else {
                    destinationUrl = MobiusConfig.getInstance().getChameleonTACCStitchPort();
                }

                stitchContext.processStitchToChamelon(stitchCount, request.getStitchTag(), request.getStitchPortUrl(),
                        request.getStitchBandwidth(), chameleonContext.getNetworkVlanId(), destinationUrl);
                stitchCount = computeResponse.getStitchCount();
                siteToContextHashMap.put(CloudContext.CloudType.Exogeni.toString(), stitchContext);
            }

        }
        catch (FutureRequestException e) {
            futureRequests.add(request);
        }
        catch (Exception e) {
            // New context was created but compute request failed to process;
            // context is not saved in this case and hence should release any open resources
            // e.g. network created for chameleon
            if(context != null && addContextToMap) {
                context.stop();
            }
            throw e;
        }
        finally {
            LOGGER.debug("OUT");
        }
        return computeResponse;
    }

    /*
     * @brief function to process storge request
     *
     * @param request - storge request
     * @param isFutureRequest - true in case this is a future request; false otherwise
     *
     * @throws Exception in case of error
     *
     */
    public void processStorageRequest(StorageRequest request, boolean isFutureRequest) throws Exception{
        LOGGER.debug("IN request=" + request + " isFutureRequest=" + isFutureRequest);
        try {
            if (siteToContextHashMap.size() == 0) {
                LOGGER.debug("OUT");
                throw new MobiusException(HttpStatus.NOT_FOUND, "target not found");
            }
            CloudContext context = null;
            for (HashMap.Entry<String, CloudContext> e : siteToContextHashMap.entrySet()) {
                context = e.getValue();
                if (context.containsHost(request.getTarget())) {
                    LOGGER.debug("Context found to handle storage request=" + context.getSite());
                    storageCount = context.processStorageRequest(request, storageCount, isFutureRequest);
                    break;
                }else {
                    context = null;
                }
            }
            if(context == null) {
                LOGGER.debug("OUT");
                throw new MobiusException(HttpStatus.NOT_FOUND, "target not found");
            }
        }
        catch (FutureRequestException e) {
            futureRequests.add(request);
        }
        finally {
            LOGGER.debug("OUT");
        }
    }

    /*
     * @brief function to find Cloud Context which contains a node with specified hostname
     *
     * @param hostname - hostname
     *
     * @throws Exception in case of error
     *
     */
    private CloudContext findContext(String hostname) throws Exception{
        // lookup source and target nodes
        LOGGER.debug("IN hostname=" + hostname);
        CloudContext context = null;
        for (HashMap.Entry<String, CloudContext> e : siteToContextHashMap.entrySet()) {
            context = e.getValue();
            if (context.containsHost(hostname)) {
                LOGGER.debug("Context found =" + context.getSite());
                break;
            }else {
                context = null;
            }
        }
        if(context == null) {
            LOGGER.debug("OUT");
            throw new MobiusException(HttpStatus.NOT_FOUND, "source not found");
        }
        LOGGER.debug("OUT");
        return context;
    }

    private boolean destinationAlreadyConnected(String siteName) {
        boolean retVal = false;
        for (String key: networkRequestToComputeRequestMap.keySet()) {
            if (key.contains(siteName)) {
                retVal = true;
                break;
            }
        }
        return retVal;
    }

    /*
     * @brief function to process network request
     *
     * @param request - network request
     * @param isFutureRequest - true in case this is a future request; false otherwise
     *
     * @throws Exception in case of error
     *
     */
    public void processNetworkRequest(NetworkRequest request, boolean isFutureRequest) throws Exception{
        LOGGER.debug("IN request=" + request + " isFutureRequest=" + isFutureRequest);
        try {
            if (siteToContextHashMap.size() == 0) {
                throw new MobiusException(HttpStatus.NOT_FOUND, "target not found");
            }

            if(request.getSource().compareToIgnoreCase(request.getDestination()) == 0) {
                throw new MobiusException(HttpStatus.BAD_REQUEST, "source and destination nodes must be different");
            }


            if(request.getAction() == NetworkRequest.ActionEnum.ADD &&
                    (request.getSourceSubnet().compareToIgnoreCase(request.getDestinationSubnet()) == 0 ||
                    request.getSourceIP().compareToIgnoreCase(request.getDestinationIP()) == 0)) {
                throw new MobiusException(HttpStatus.BAD_REQUEST, "source and destination subnet/ip must be different");
            }

            // lookup source and target nodes
            CloudContext context1 = findContext(request.getSource());
            CloudContext context2 = findContext(request.getDestination());

            String site1 = context1.getSite();
            String site2 = context2.getSite();
            String destHostOrSite = request.getDestination();
            String sourceHostOrSite = request.getSource();
            String sdxStitchPortInterfaceIP  = null;
            if(site1.contains(CloudContext.CloudType.Chameleon.toString()) == true) {
                destHostOrSite = site2.substring(site2.lastIndexOf(":") + 1);
                sdxStitchPortInterfaceIP = request.getChameleonSdxControllerIP();
            }
            if(site2.contains(CloudContext.CloudType.Chameleon.toString()) == true) {
                sourceHostOrSite = site1.substring(site1.lastIndexOf(":") + 1);
                sdxStitchPortInterfaceIP = request.getChameleonSdxControllerIP();
            }

            // Stitch to SDX and advertise the prefix or Unstitch
            if(!destinationAlreadyConnected(request.getSource())) {
                context1.processNetworkRequestSetupStitchingAndRoute(request.getSource(), request.getSourceIP(),
                        request.getSourceSubnet(), request.getSourceLocalSubnet(), request.getAction(),
                        destHostOrSite, sdxStitchPortInterfaceIP);
            }

            // Stitch to SDX and advertise the prefix or Unstitch
            if(!destinationAlreadyConnected(request.getDestination())) {
                context2.processNetworkRequestSetupStitchingAndRoute(request.getDestination(), request.getDestinationIP(),
                        request.getDestinationSubnet(), request.getDestLocalSubnet(), request.getAction(),
                        sourceHostOrSite, sdxStitchPortInterfaceIP);
            }

            if(request.getAction() == NetworkRequest.ActionEnum.ADD) {
                // Connect the prefix source - destination
                context1.processNetworkRequestLink(request.getSource(), request.getSourceSubnet(),
                        request.getDestinationSubnet(), request.getLinkSpeed(), request.getDestinationIP(),
                        sdxStitchPortInterfaceIP);

                // Connect the prefix destination - source
                //context2.processNetworkRequestLink(request.getDestination(), request.getDestinationSubnet(),
                //        request.getSourceSubnet(), request.getLinkSpeed(), request.getSourceIP(),
                //        sdxStitchPortInterfaceIP);

                if(request.getSourceLocalSubnet() != null) {
                    // Connect the prefix source - destination
                    context1.processNetworkRequestLink(request.getSource(), request.getSourceLocalSubnet(),
                            request.getDestinationSubnet(), request.getLinkSpeed(), request.getDestinationIP(),
                            sdxStitchPortInterfaceIP);

                    // Connect the prefix destination - source
                    //context2.processNetworkRequestLink(request.getDestination(), request.getDestinationSubnet(),
                    //        request.getSourceLocalSubnet(), request.getLinkSpeed(), request.getSourceIP(),
                    //        sdxStitchPortInterfaceIP);

                }

                if(request.getDestLocalSubnet() != null) {
                    // Connect the prefix source - destination
                    context1.processNetworkRequestLink(request.getSource(), request.getSourceSubnet(),
                            request.getDestLocalSubnet(), request.getLinkSpeed(), request.getDestinationIP(),
                            sdxStitchPortInterfaceIP);

                    // Connect the prefix destination - source
                    //context2.processNetworkRequestLink(request.getDestination(), request.getDestLocalSubnet(),
                    //        request.getSourceSubnet(), request.getLinkSpeed(), request.getSourceIP(), sdxStitchPortInterfaceIP);

                }
                String key = request.getSource() + "+" + request.getDestination();
                JSONObject object = new JSONObject();
                object.put("source", request.getSource());
                object.put("sourceIP", request.getSourceIP());
                object.put("sourceSubnet",request.getSourceSubnet());
                if (request.getSourceLocalSubnet() != null) {
                    object.put("sourceLocalSubnet", request.getSourceLocalSubnet());
                }
                object.put("destination",request.getDestination());
                object.put("destinationIP",request.getDestinationIP());
                object.put("destinationSubnet",request.getDestinationSubnet());
                if (request.getDestLocalSubnet() != null) {
                    object.put("destLocalSubnet", request.getDestLocalSubnet());
                }
                object.put("linkSpeed",request.getLinkSpeed());
                if(request.getChameleonSdxControllerIP() != null) {
                    object.put("chameleonSdxControllerIP", request.getChameleonSdxControllerIP());
                }
                LOGGER.debug("Adding " + key + " networkRequest=" + object.toString());
                networkRequestToComputeRequestMap.put(key, object.toString());
            }
            else if(request.getAction() == NetworkRequest.ActionEnum.DELETE) {
                String key = request.getSource() + "+" + request.getDestination();
                networkRequestToComputeRequestMap.remove(key);
            }
        }
        finally {
            LOGGER.debug("OUT");
        }
    }
    /*
     * @brief function to process a stitch request;
     *
     * @param request - stitch request
     * @param isFutureRequest - true in case this is a future request; false otherwise
     *
     * @throws Exception in case of error
     *
     *
     */
    public void processStitchRequest(StitchRequest request, boolean isFutureRequest) throws Exception{
        LOGGER.debug("IN request=" + request + " isFutureRequest=" + isFutureRequest);
        try {
            if (siteToContextHashMap.size() == 0) {
                throw new MobiusException(HttpStatus.NOT_FOUND, "target not found");
            }
            CloudContext context = null;
            for (HashMap.Entry<String, CloudContext> e : siteToContextHashMap.entrySet()) {
                context = e.getValue();
                if (context.containsHost(request.getTarget())) {
                    LOGGER.debug("Context found to handle storage request=" + context.getSite());
                    stitchCount = context.processStitchRequest(request, stitchCount, isFutureRequest);
                    break;
                }else {
                    context = null;
                }
            }
            if(context == null) {
                LOGGER.debug("OUT");
                throw new MobiusException(HttpStatus.NOT_FOUND, "target not found");
            }
        }
        catch (FutureRequestException e) {
            futureRequests.add(request);
        }
        finally {
            LOGGER.debug("OUT");
        }
    }

    /*
     * @brief function to process a Script request;
     *
     * @param request - Script request
     * @param isFutureRequest - true in case this is a future request; false otherwise
     *
     * @throws Exception in case of error
     *
     *
     */
    public void processScriptRequest(ScriptRequest request, boolean isFutureRequest) throws Exception{
        LOGGER.debug("IN request=" + request + " isFutureRequest=" + isFutureRequest);
        try {
            if (siteToContextHashMap.size() == 0) {
                throw new MobiusException(HttpStatus.NOT_FOUND, "target not found");
            }
            CloudContext context = null;
            for (HashMap.Entry<String, CloudContext> e : siteToContextHashMap.entrySet()) {
                context = e.getValue();
                if (context.containsHost(request.getTarget())) {
                    LOGGER.debug("Context found to handle script request=" + context.getSite());

                    String cometHost = MobiusConfig.getInstance().getCometHost();
                    String caCert = MobiusConfig.getInstance().getCometCaCert();
                    String cert = MobiusConfig.getInstance().getCometCert();
                    String certPwd = MobiusConfig.getInstance().getCometCertPwd();

                    String site = context.getSite();
                    String target = request.getTarget();

                    if(site.contains(CloudContext.CloudType.Chameleon.toString()) == true ||
                            site.contains(CloudContext.CloudType.Jetstream.toString()) == true ||
                            site.contains(CloudContext.CloudType.Mos.toString()) == true) {
                        if (target.contains(".novalocal") == false) {
                            target += ".novalocal";
                        }

                    }

                    if(cometHost != null && caCert != null && cert != null && certPwd != null) {
                        CometDataManager cometDataManager = new CometDataManager(cometHost, caCert, cert, certPwd);
                        cometDataManager.createScriptEntry(workflowID, target, request.getName(), request.getScript());
                    }
                    break;
                }else {
                    context = null;
                }
            }
            if(context == null) {
                LOGGER.debug("OUT");
                throw new MobiusException(HttpStatus.NOT_FOUND, "target not found");
            }
        }
        finally {
            LOGGER.debug("OUT");
        }
    }

    public void processSdxPrefix(SdxPrefix request) throws Exception {
        LOGGER.debug("IN request=" + request);
        try {
            if (siteToContextHashMap.size() == 0) {
                throw new MobiusException(HttpStatus.NOT_FOUND, "target not found");
            }

            // lookup source and target nodes
            CloudContext context1 = findContext(request.getSource());

            if (context1 == null)
                throw new MobiusException("conetxt for node: " + request.getSource() + " not found");


            context1.processSdxPrefix(request);
        }
        finally {
            LOGGER.debug("OUT");
        }
    }

    /*
     * @brief performs following periodic actions
     *        - Reload hostnames of all instances
     *        - Reload hostNameToSliceNameHashMap
     *        - Determine if notification to pegasus should be triggered
     *        - Build notification JSON object
     *        - process future requests
     *
     * @return JSONObject representing notification for context to be sent to pegasus
     */
    public void doPeriodic() {
        LOGGER.debug("IN");

        JSONArray array = new JSONArray();
        CloudContext context = null;
        boolean triggerNotification = false;
        for(HashMap.Entry<String, CloudContext> e : siteToContextHashMap.entrySet()) {
            context = e.getValue();
            JSONObject result = context.doPeriodic();
            if(result != null && !result.isEmpty()) {
                array.add(result);
            }
            triggerNotification |= context.isTriggerNotification();
            if(context.isTriggerNotification()) {
                context.setTriggerNotification(false);
            }
        }
        String notification = array.toString();
        if(triggerNotification && !notification.isEmpty()) {
            if(NotificationPublisher.getInstance().isConnected()) {
                LOGGER.debug("Sending notification to Pegasus = " + notification);
                NotificationPublisher.getInstance().push(workflowID, notification);
            }
            else {
                LOGGER.debug("Unable to send notification to Pegasus = " + notification);
            }
        }
        // Process future requests
        processFutureComputeRequests();
        processFutureStorageRequests();
        LOGGER.debug("OUT");
    }

    /*
     * @brief check and trigger any future compute requests if their startTime is current time
     */
    public void processFutureComputeRequests() {
        LOGGER.debug("IN");
        try {
            List<ComputeRequest> computeRequests = futureRequests.getFutureComputeRequests();
            Iterator iterator = computeRequests.iterator();
            while (iterator.hasNext()) {
                ComputeRequest request = (ComputeRequest) iterator.next();
                try {
                    processComputeRequest(request, true);
                }
                catch (FutureRequestException e)
                {
                    LOGGER.debug("future request");
                }
                catch (Exception e) {
                    LOGGER.error("Error occurred while processing future compute request = " + e.getMessage());
                    e.printStackTrace();
                }
                finally {
                    futureRequests.remove(request);
                }
            }
        }
        catch (Exception e) {
            LOGGER.error("Error occurred while processing future compute request = " + e.getMessage());
            e.printStackTrace();
        }
        LOGGER.debug("OUT");
    }

    /*
     * @brief check and trigger any future storage requests if their startTime is current time
     */
    public void processFutureStorageRequests() {
        LOGGER.debug("IN");

        try {
            List<StorageRequest> storageRequests = futureRequests.getFutureStorageRequests();
            Iterator iterator = storageRequests.iterator();
            while (iterator.hasNext()) {
                StorageRequest request = (StorageRequest) iterator.next();
                try {
                    processStorageRequest(request, true);
                }
                catch (FutureRequestException e)
                {
                    LOGGER.debug("future request");
                }
                catch (Exception e) {
                    LOGGER.error("Error occurred while processing future compute request = " + e.getMessage());
                    e.printStackTrace();
                }
                finally {
                    futureRequests.remove(request);
                }
            }
        }
        catch (Exception e) {
            LOGGER.error("Error occurred while processing future compute request = " + e.getMessage());
            e.printStackTrace();
        }
        LOGGER.debug("OUT");
    }
}