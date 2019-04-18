package org.renci.controllers.os;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closeables;
import com.google.inject.Module;
import org.jclouds.ContextBuilder;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.keystone.config.KeystoneProperties;
import org.jclouds.openstack.neutron.v2.NeutronApi;
import org.jclouds.openstack.neutron.v2.NeutronApiMetadata;
import org.jclouds.openstack.neutron.v2.domain.*;
import org.jclouds.openstack.neutron.v2.extensions.RouterApi;
import org.jclouds.openstack.neutron.v2.features.NetworkApi;
import org.jclouds.openstack.neutron.v2.features.SubnetApi;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/*
 * @brief class represents api to provision compute resources on openstack via NEUTRON API
 *
 * @author kthare10
 */
public class NetworkController implements Closeable {
    public final static String NetworkId = "networkId";
    public final static String SubnetId = "subnetId";
    public final static String RouterId = "routerId";

    private String authUrl;
    private String user;
    private String password;
    private String domain;
    private String project;
    private final NeutronApi neutronApi;

    /*
     * @brief constructor
     *
     * @param authUrl - auth url for chameleon
     * @parm username - chameleon user name
     * @param password - chameleon user password
     * @param domain - chameleon user domain
     * @param project - chameleon project Name
     */
    public NetworkController(String authUrl, String user, String password, String domain, String project) {
        this.authUrl = authUrl;
        this.user = user;
        this.password = password;
        this.domain = domain;
        this.project = project;

        Iterable<Module> modules = ImmutableSet.<Module>of(new SLF4JLoggingModule());

        // Please refer to 'Keystone v2-v3 authentication' section for complete authentication use case
        final Properties overrides = new Properties();
        overrides.put(KeystoneProperties.KEYSTONE_VERSION, "3");
        overrides.put(KeystoneProperties.SCOPE, "project:" + project);

        String identity = domain + ":" + user;

        neutronApi = ContextBuilder.newBuilder(new NeutronApiMetadata())
                .endpoint(authUrl)
                .credentials(identity, password)
                .overrides(overrides)
                .modules(modules)
                .buildApi(NeutronApi.class);
    }

    /*
     * @brief provision a network on openstack
     *
     * @param region - region
     * @param physicalNetworkName - physicalNetworkName
     * @param externalNetworkId - externalNetworkId
     * @param shared - true if network is shared, false otherwise
     * @param cidr - network cidr
     * @param name - name
     *
     * @return map containing networkId, routerId and subnetId
     *
     * @throws exception in case of error
     */
    public Map<String, String> createNetwork(String region, String physicalNetworkName,
                                             String externalNetworkId, boolean shared,
                                             String cidr, String name) throws Exception{
        NetworkApi networkApi = neutronApi.getNetworkApi(region);
        Network net = null;
        Map<String, String> retVal = null;
        try {
            Network.CreateBuilder createBuilder = Network.createBuilder(name)
                    .networkType(NetworkType.VLAN)
                    .physicalNetworkName(physicalNetworkName)
                    .shared(shared);
            net = networkApi.create(createBuilder.build());
            String subnetName = name + "subnet";
            Subnet subnet = createSubnet(region, net.getId(), true, 4, cidr, subnetName);
            String routerName = name + "router";
            Router router = createRouter(region, externalNetworkId, routerName);
            attachSubnet(region, router.getId(), subnet.getId());
            retVal = new HashMap<>();
            retVal.put(NetworkId, net.getId());
            retVal.put(SubnetId, subnet.getId());
            retVal.put(RouterId, router.getId());

        } catch (Exception e){
            if(net != null) {
                networkApi.delete(net.getId());
                net = null;
            }
            System.out.println("Exception occured while creating network " + name + " e=" + e);
            throw e;
        }
        return retVal;
    }

    /*
     * @brief provision a sub network on openstack
     *
     * @param region - region
     * @param netId - netId
     * @param enableDhcp - enableDhcp
     * @param ipVersion - ipVersion
     * @param cidr - network cidr
     * @param name - name
     *
     * @return subnetId
     *
     * @throws exception in case of error
     */
    private Subnet createSubnet(String region, String netId, boolean enableDhcp, Integer ipVersion,
                                String cidr, String name) throws Exception {
        SubnetApi subnetApi = neutronApi.getSubnetApi(region);
        Subnet subnet = null;
        try {

            subnet = subnetApi.create(Subnet.createBuilder(netId, cidr)
                    .ipVersion(ipVersion)
                    .enableDhcp(enableDhcp)
                    .name(name)
                    .build());
        } catch (Exception e){
            if(subnet != null) {
                subnetApi.delete(subnet.getId());
            }
            System.out.println("Exception occured while creating subnet " + name + " e=" + e);
            throw e;
        }
        return subnet;
    }

    /*
     * @brief delete a sub network on openstack
     *
     * @param region - region
     * @param subnetId - subnetId
     *
     */
    private void deleteSubnet(String region, String subnetId) {
        SubnetApi subnetApi = neutronApi.getSubnetApi(region);
        try {

            subnetApi.delete(subnetId);
        } catch (Exception e){
            System.out.println("Exception occured while deleting subnet " + subnetId + " e=" + e);
        }
    }

    /*
     * @brief provision a router on openstack
     *
     * @param region - region
     * @param externalNetworkId - externalNetworkId
     * @param name - name
     *
     * @return routerId
     *
     * @throws exception in case of error
     */
    private Router createRouter(String region, String externalNetworkId, String name) throws Exception {
        Optional<RouterApi> routerApi = neutronApi.getRouterApi(region);
        Router router = null;
        ExternalGatewayInfo externalGatewayInfo = null;
        try {
            if(routerApi.isPresent()) {
                externalGatewayInfo = ExternalGatewayInfo.builder().networkId(externalNetworkId).build();

                router = routerApi.get().create(Router.createBuilder()
                        .adminStateUp(true)
                        .name(name)
                        .externalGatewayInfo(externalGatewayInfo)
                        .build());
            }

        } catch (Exception e){
            if(router != null) {
                routerApi.get().delete(router.getId());
            }
            System.out.println("Exception occured while creating router " + name + " e=" + e);
            throw e;
        }
        return router;
    }

    /*
     * @brief delete a router on openstack
     *
     * @param region - region
     * @param routerId - routerId
     *
     */
    private void deleteRouter(String region, String routerId) {
        Optional<RouterApi> routerApi = neutronApi.getRouterApi(region);
        try {
            if(routerApi.isPresent()) {
                routerApi.get().delete(routerId);
            }

        } catch (Exception e){
            System.out.println("Exception occured while deleting router " + routerId + " e=" + e);
        }
    }

    /*
     * @brief attach a subnet to router
     *
     * @param region - region
     * @param routerId - routerId
     * @param subnetId - subnetId
     *
     * @return RouterInterface
     *
     * @throws exception in case of failure
     *
     */
    private RouterInterface attachSubnet(String region, String routerId, String subnetId) throws Exception{
        Optional<RouterApi> routerApi = neutronApi.getRouterApi(region);
        RouterInterface routerInterface = null;
        try {
            if(routerApi.isPresent()) {

                routerInterface = routerApi.get().addInterfaceForSubnet(routerId, subnetId);
            }

        } catch (Exception e){
            if(routerInterface != null) {
                routerApi.get().removeInterfaceForSubnet(routerId, subnetId);
            }
            System.out.println("Exception occured while attaching subnet " + subnetId + " to " + routerId + " e=" + e);
            throw e;
        }
        return routerInterface;
    }

    /*
     * @brief detach a subnet from router
     *
     * @param region - region
     * @param routerId - routerId
     * @param subnetId - subnetId
     *
     *
     */
    private void detachSubnet(String region, String routerId, String subnetId) {
        Optional<RouterApi> routerApi = neutronApi.getRouterApi(region);
        try {
            if(routerApi.isPresent()) {
                routerApi.get().removeInterfaceForSubnet(routerId, subnetId);
            }

        } catch (Exception e){
            System.out.println("Exception occured while attaching subnet " + subnetId + " to " + routerId + " e=" + e);
        }
    }

    /*
     * @brief delete a network
     *
     * @param region - region
     * @param ids - map of networkId, routerId and subnetId
     * @param maxRetries - maxRetries
     *
     */
    public void deleteNetwork(String region, Map<String, String> ids, int maxRetries) {
        try {
            for (int i = 0; i < maxRetries; ++i) {
                try {
                    if (ids.containsKey(SubnetId) && ids.containsKey(RouterId)) {
                        System.out.println("Removing subnet: " + ids.get(SubnetId) + " from router: " + ids.get(RouterId));
                        detachSubnet(region, ids.get(RouterId), ids.get(SubnetId));
                    }

                    if (ids.containsKey(RouterId)) {
                        System.out.println("Deleting router: " + ids.get(RouterId));
                        deleteRouter(region, ids.get(RouterId));
                    }

                    if (ids.containsKey(SubnetId)) {
                        System.out.println("Deleting subnet: " + ids.get(SubnetId));
                        deleteSubnet(region, ids.get(SubnetId));
                    }

                    if (ids.containsKey(NetworkId)) {
                        System.out.println("Deleting network: " + ids.get(NetworkId));
                        NetworkApi networkApi = neutronApi.getNetworkApi(region);
                        networkApi.delete(ids.get(NetworkId));
                    }
                } catch (Exception e) {
                    System.out.println("Exception occured while deleting network e=" + e);
                    TimeUnit.SECONDS.sleep(1);
                    continue;
                }
                break;
            }
        }
        catch (Exception e) {
            System.out.println("Exception occured while deleting network e=" + e);
        }
    }

    /*
     * @brief determine network id give network name
     *
     * @param region - region
     * @param networkName - networkName
     *
     * @return networkId
     */
    public String getNetworkId(String region, String networkName) {
        org.jclouds.openstack.neutron.v2.domain.Network network = null;
        NetworkApi networkApi = neutronApi.getNetworkApi(region);

        for (org.jclouds.openstack.neutron.v2.domain.Network thisNetwork : networkApi.list().concat()) {
            if (thisNetwork.getName().equals(networkName)) {
                network = thisNetwork;
            }
        }
        if(network != null) {
            return network.getId();
        }
        return null;
    }

    /*
     * @brief close the controller
     *
     */
    public void close() {
        try {
            Closeables.close(neutronApi, true);
        }
        catch (Exception e) {
            System.out.println("Exception occured while closing e=" + e);
        }
    }
}