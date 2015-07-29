/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 *  Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.model.varray;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.List;

@XmlRootElement(name = "varray_available_attributes")
public class VArrayAttributeList {
    private List<AttributeList> attributes;

    public VArrayAttributeList() {
    }

    public VArrayAttributeList(
            List<AttributeList> attributes) {
        this.attributes = attributes;
    }

    /**
     * A list of virtual pool available attribute response instances.
     * 
     * @valid none
     * 
     * @return A list of virtual pool available attribute response instances.
     */
    @XmlElement(name = "varray_attributes")
    public List<AttributeList> getAttributes() {
        if (attributes == null) {
            attributes = new ArrayList<AttributeList>();
        }
        return attributes;
    }

    public void setAttributes(List<AttributeList> attributes) {
        this.attributes = attributes;
    }
}
