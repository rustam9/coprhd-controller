package com.emc.storageos.cinder.model;

import java.util.Map;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import com.emc.storageos.model.RestLinkRep;

public class ConsistencyGroupDetail {	
	public String description;
	public String created_at;	
	public String availability_zone;
	public String id;
	public String name;	
}
