/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.cinder.model;

import java.util.Map;

public class VolumeCreateRequest {
	
	/**
	 * Json model representation for volume
	 * create request
	 * 
	 * {"volume":{
      		"availability_zone":null,
      		"source_volid":null,
      		"display_description":null,
      		"snapshot_id":null,
      		"size":10,
      		"display_name":"my_volume",
      		"imageRef":null,
      		"volume_type":null,
      		"metadata":{
 
      			}
   			}
		}
	 */
	public Volume volume = new Volume();	
	public class Volume
	{
		public String status;
		public String availability_zone;
		public String source_volid;
		public String description;
		public String snapshot_id;
		public String consistencygroup_id;
		public String source_replica;
		public String user_id;
		public long size;
		public String name;
		public String imageRef;
		public String attach_status;
		public String volume_type;
		public String project_id;
		public Map<String, String> metadata;
	}
	

}
