package com.emc.sa.service.vipr.block.consistency;

import static com.emc.sa.service.ServiceParams.CONSISTENCY_GROUP;
import static com.emc.sa.service.ServiceParams.SNAPSHOTS;
import static com.emc.sa.service.vipr.block.consistency.ConsistencyUtils.restoreSnapshot;

import java.net.URI;
import java.util.List;

import com.emc.sa.engine.bind.Param;
import com.emc.sa.engine.service.Service;
import com.emc.sa.service.vipr.ViPRService;

@Service("ConsistencyGroupRestoreSnapshot")
public class RestoreSnapshotService extends ViPRService {

	@Param(CONSISTENCY_GROUP)
	protected URI consistencyGroupId;

	@Param(SNAPSHOTS)
	protected List<String> snapshotIds;

	@Override
	public void execute() throws Exception {
		for (URI snapshotId : uris(snapshotIds)) {
			restoreSnapshot(consistencyGroupId, snapshotId);
		}
	}
}