/**
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.scaleio.api.restapi.response;

import java.util.List;

public class ScaleIOSds {
    private String id;
    private List<Ip> ipList;
    private String protectionDomainId;
    private String name;
    private String sdsState;
    private String port;
    
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public List<Ip> getIpList() {
        return ipList;
    }
    public void setIpList(List<Ip> ipList) {
        this.ipList = ipList;
    }
    public String getProtectionDomainId() {
        return protectionDomainId;
    }
    public void setProtectionDomainId(String protectionDomainId) {
        this.protectionDomainId = protectionDomainId;
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getSdsState() {
        return sdsState;
    }
    public void setSdsState(String sdsState) {
        this.sdsState = sdsState;
    }
    public String getPort() {
        return port;
    }
    public void setPort(String port) {
        this.port = port;
    }
    
    public class Ip {
        private String role;
        private String ip;
        public String getRole() {
            return role;
        }
        public void setRole(String role) {
            this.role = role;
        }
        public String getIp() {
            return ip;
        }
        public void setIp(String ip) {
            this.ip = ip;
        }
        
        
    }
    
}