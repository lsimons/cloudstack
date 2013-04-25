/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.motion;

import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionStrategy;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObjectType;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;

import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageCacheManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.BackupSnapshotAnswer;
import com.cloud.agent.api.BackupSnapshotCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreatePrivateTemplateFromSnapshotCommand;
import com.cloud.agent.api.CreatePrivateTemplateFromVolumeCommand;
import com.cloud.agent.api.CreateVolumeFromSnapshotAnswer;
import com.cloud.agent.api.CreateVolumeFromSnapshotCommand;
import com.cloud.agent.api.UpgradeSnapshotCommand;
import com.cloud.agent.api.storage.CopyVolumeAnswer;
import com.cloud.agent.api.storage.CopyVolumeCommand;
import com.cloud.agent.api.storage.CreatePrivateTemplateAnswer;
import com.cloud.agent.api.to.S3TO;
import com.cloud.agent.api.to.SwiftTO;
import com.cloud.configuration.Config;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.s3.S3Manager;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.storage.swift.SwiftManager;
import com.cloud.template.TemplateManager;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class AncientDataMotionStrategy implements DataMotionStrategy {
    private static final Logger s_logger = Logger
            .getLogger(AncientDataMotionStrategy.class);
    @Inject
    EndPointSelector selector;
    @Inject
    TemplateManager templateMgr;
    @Inject
    VolumeDataStoreDao volumeStoreDao;
    @Inject
    HostDao hostDao;
    @Inject
    ConfigurationDao configDao;
    @Inject
    StorageManager storageMgr;
    @Inject
    VolumeDao volDao;
    @Inject
    VMTemplateDao templateDao;
    @Inject
    SnapshotManager snapshotMgr;
    @Inject
    SnapshotDao snapshotDao;
    @Inject
    SnapshotDataStoreDao _snapshotStoreDao;
    @Inject
    PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    DataStoreManager dataStoreMgr;
    @Inject
    TemplateDataStoreDao templateStoreDao;
    @Inject DiskOfferingDao diskOfferingDao;
    @Inject VMTemplatePoolDao templatePoolDao;
    @Inject
    VolumeManager volumeMgr;
    @Inject
    private SwiftManager _swiftMgr;
    @Inject
    private S3Manager _s3Mgr;
    @Inject
    StorageCacheManager cacheMgr;

    @Override
    public boolean canHandle(DataObject srcData, DataObject destData) {
        // TODO Auto-generated method stub
        return true;
    }

    @DB
    protected Answer copyVolumeFromImage(DataObject srcData, DataObject destData) {
        String value = configDao.getValue(Config.CopyVolumeWait.key());
        int _copyvolumewait = NumbersUtil.parseInt(value,
                Integer.parseInt(Config.CopyVolumeWait.getDefaultValue()));

        if (srcData.getDataStore().getRole() != DataStoreRole.ImageCache && destData.getDataStore().getRole() != DataStoreRole.ImageCache) {
            //need to copy it to image cache store
            DataObject cacheData = cacheMgr.createCacheObject(srcData, destData.getDataStore().getScope());
            CopyCommand cmd = new CopyCommand(cacheData.getTO(), destData.getTO(), _copyvolumewait);
            EndPoint ep = selector.select(cacheData, destData);
            Answer answer = ep.sendMessage(cmd);
            return answer;
        } else {
            //handle copy it to/from cache store
            CopyCommand cmd = new CopyCommand(srcData.getTO(), destData.getTO(), _copyvolumewait);
            EndPoint ep = selector.select(srcData, destData);
            Answer answer = ep.sendMessage(cmd);
            return answer;
        }
    }

    private Answer copyTemplate(DataObject srcData, DataObject destData) {
        String value = configDao.getValue(Config.PrimaryStorageDownloadWait.toString());
        int _primaryStorageDownloadWait = NumbersUtil.parseInt(value, Integer.parseInt(Config.PrimaryStorageDownloadWait.getDefaultValue()));
        if (srcData.getDataStore().getRole() != DataStoreRole.ImageCache && destData.getDataStore().getRole() != DataStoreRole.ImageCache) {
            //need to copy it to image cache store
            DataObject cacheData = cacheMgr.createCacheObject(srcData, destData.getDataStore().getScope());
            CopyCommand cmd = new CopyCommand(cacheData.getTO(), destData.getTO(), _primaryStorageDownloadWait);
            EndPoint ep = selector.select(cacheData, destData);
            Answer answer = ep.sendMessage(cmd);
            return answer;
        } else {
            //handle copy it to/from cache store
            CopyCommand cmd = new CopyCommand(srcData.getTO(), destData.getTO(), _primaryStorageDownloadWait);
            EndPoint ep = selector.select(srcData, destData);
            Answer answer = ep.sendMessage(cmd);
            return answer;
        }
    }

    protected Answer copyFromSnapshot(DataObject snapObj, DataObject volObj) {
        SnapshotVO snapshot = this.snapshotDao.findById(snapObj.getId());
        StoragePool pool = (StoragePool) volObj.getDataStore();
        String vdiUUID = null;
        Long snapshotId = snapshot.getId();
        Long volumeId = snapshot.getVolumeId();
        Long dcId = snapshot.getDataCenterId();
        String secondaryStoragePoolUrl = this.snapshotMgr
                .getSecondaryStorageURL(snapshot);
        long accountId = snapshot.getAccountId();

        String backedUpSnapshotUuid = snapshot.getBackupSnapshotId();
        snapshot = snapshotDao.findById(snapshotId);
        if (snapshot.getVersion().trim().equals("2.1")) {
            VolumeVO volume = this.volDao.findByIdIncludingRemoved(volumeId);
            if (volume == null) {
                throw new CloudRuntimeException("failed to upgrade snapshot "
                        + snapshotId + " due to unable to find orignal volume:"
                        + volumeId + ", try it later ");
            }
            if (volume.getTemplateId() == null) {
                snapshotDao.updateSnapshotVersion(volumeId, "2.1", "2.2");
            } else {
                VMTemplateVO template = templateDao
                        .findByIdIncludingRemoved(volume.getTemplateId());
                if (template == null) {
                    throw new CloudRuntimeException(
                            "failed to upgrade snapshot "
                                    + snapshotId
                                    + " due to unalbe to find orignal template :"
                                    + volume.getTemplateId()
                                    + ", try it later ");
                }
                Long templateId = template.getId();
                Long tmpltAccountId = template.getAccountId();
                if (!snapshotDao.lockInLockTable(snapshotId.toString(), 10)) {
                    throw new CloudRuntimeException(
                            "failed to upgrade snapshot "
                                    + snapshotId
                                    + " due to this snapshot is being used, try it later ");
                }
                UpgradeSnapshotCommand cmd = new UpgradeSnapshotCommand(null,
                        secondaryStoragePoolUrl, dcId, accountId, volumeId,
                        templateId, tmpltAccountId, null,
                        snapshot.getBackupSnapshotId(), snapshot.getName(),
                        "2.1");
                Answer answer = null;
                try {
                    answer = this.storageMgr.sendToPool(pool, cmd);
                } catch (StorageUnavailableException e) {
                } finally {
                    snapshotDao.unlockFromLockTable(snapshotId.toString());
                }
                if ((answer != null) && answer.getResult()) {
                    snapshotDao.updateSnapshotVersion(volumeId, "2.1", "2.2");
                } else {
                    throw new CloudRuntimeException("Unable to upgrade snapshot from 2.1 to 2.2 for "
                            + snapshot.getId());
                }
            }
        }
        String basicErrMsg = "Failed to create volume from "
                + snapshot.getName() + " on pool " + pool;

        try {
            if (snapshot.getSwiftId() != null && snapshot.getSwiftId() != 0) {
                snapshotMgr.downloadSnapshotsFromSwift(snapshot);
            } else if (snapshot.getS3Id() != null && snapshot.getS3Id() != 0) {
                snapshotMgr.downloadSnapshotsFromS3(snapshot);
            }
            String value = configDao
                    .getValue(Config.CreateVolumeFromSnapshotWait.toString());
            int _createVolumeFromSnapshotWait = NumbersUtil.parseInt(value,
                    Integer.parseInt(Config.CreateVolumeFromSnapshotWait
                            .getDefaultValue()));
            CreateVolumeFromSnapshotCommand createVolumeFromSnapshotCommand = new CreateVolumeFromSnapshotCommand(
                    pool, secondaryStoragePoolUrl, dcId, accountId, volumeId,
                    backedUpSnapshotUuid, snapshot.getName(),
                    _createVolumeFromSnapshotWait);
            CreateVolumeFromSnapshotAnswer answer;
            if (!snapshotDao.lockInLockTable(snapshotId.toString(), 10)) {
                throw new CloudRuntimeException("failed to create volume from "
                        + snapshotId
                        + " due to this snapshot is being used, try it later ");
            }
            answer = (CreateVolumeFromSnapshotAnswer) this.storageMgr
                    .sendToPool(pool, createVolumeFromSnapshotCommand);
            if (answer != null && answer.getResult()) {
                vdiUUID = answer.getVdi();
                VolumeVO vol = this.volDao.findById(volObj.getId());
                vol.setPath(vdiUUID);
                this.volDao.update(vol.getId(), vol);
                return null;
            } else {
                s_logger.error(basicErrMsg + " due to "
                        + ((answer == null) ? "null" : answer.getDetails()));
                throw new CloudRuntimeException(basicErrMsg);
            }
        } catch (StorageUnavailableException e) {
            s_logger.error(basicErrMsg, e);
            throw new CloudRuntimeException(basicErrMsg);
        } finally {
            if (snapshot.getSwiftId() != null) {
                snapshotMgr.deleteSnapshotsDirForVolume(
                        secondaryStoragePoolUrl, dcId, accountId, volumeId);
            }
        }
    }

    protected Answer cloneVolume(DataObject template, DataObject volume) {
        CopyCommand cmd = new CopyCommand(template.getTO(), volume.getTO(), 0);
        StoragePool pool = (StoragePool)volume.getDataStore();

        try {
            Answer answer = storageMgr.sendToPool(pool, null, cmd);
            return answer;
        } catch (StorageUnavailableException e) {
            s_logger.debug("Failed to send to storage pool", e);
            throw new CloudRuntimeException("Failed to send to storage pool", e);
        }
    }

    protected Answer copyVolumeBetweenPools(DataObject srcData, DataObject destData) {
        String value = configDao.getValue(Config.CopyVolumeWait.key());
        int _copyvolumewait = NumbersUtil.parseInt(value,
                Integer.parseInt(Config.CopyVolumeWait.getDefaultValue()));

        DataObject cacheData = cacheMgr.createCacheObject(srcData, destData.getDataStore().getScope());
        CopyCommand cmd = new CopyCommand(cacheData.getTO(), destData.getTO(), _copyvolumewait);
        EndPoint ep = selector.select(cacheData, destData);
        Answer answer = ep.sendMessage(cmd);
        return answer;
    }

    @Override
    public Void copyAsync(DataObject srcData, DataObject destData,
            AsyncCompletionCallback<CopyCommandResult> callback) {
        Answer answer = null;
        String errMsg = null;
        try {
            if (destData.getType() == DataObjectType.VOLUME
                    && srcData.getType() == DataObjectType.VOLUME && srcData.getDataStore().getRole() == DataStoreRole.Image) {
            	answer = copyVolumeFromImage(srcData, destData);
            } else if (destData.getType() == DataObjectType.TEMPLATE
                    && srcData.getType() == DataObjectType.TEMPLATE) {
            	answer = copyTemplate(srcData, destData);
            } else if (srcData.getType() == DataObjectType.SNAPSHOT
                    && destData.getType() == DataObjectType.VOLUME) {
            	answer = copyFromSnapshot(srcData, destData);
            } else if (srcData.getType() == DataObjectType.SNAPSHOT
                    && destData.getType() == DataObjectType.TEMPLATE) {
            	answer = createTemplateFromSnapshot(srcData, destData);
            } else if (srcData.getType() == DataObjectType.VOLUME
                    && destData.getType() == DataObjectType.TEMPLATE) {
            	answer = createTemplateFromVolume(srcData, destData);
            } else if (srcData.getType() == DataObjectType.TEMPLATE
                    && destData.getType() == DataObjectType.VOLUME) {
            	answer = cloneVolume(srcData, destData);
            } else if (destData.getType() == DataObjectType.VOLUME
                    && srcData.getType() == DataObjectType.VOLUME && srcData.getDataStore().getRole() == DataStoreRole.Primary) {
            	answer = copyVolumeBetweenPools(srcData, destData);
            } else if (srcData.getType() == DataObjectType.SNAPSHOT &&
            		destData.getType() == DataObjectType.SNAPSHOT) {
            	answer = copySnapshot(srcData, destData);
            }
        } catch (Exception e) {
            s_logger.debug("copy failed", e);
            errMsg = e.toString();
        }
        CopyCommandResult result = new CopyCommandResult(null, answer);
        result.setResult(errMsg);
        callback.complete(result);

        return null;
    }

    @DB
    protected Answer createTemplateFromSnapshot(DataObject srcData,
            DataObject destData) {
        long snapshotId = srcData.getId();
        SnapshotVO snapshot = snapshotDao.findById(snapshotId);
        if (snapshot == null) {
            throw new CloudRuntimeException("Unable to find Snapshot for Id "
                    + srcData.getId());
        }
        Long zoneId = snapshot.getDataCenterId();
        DataStore secStore = destData.getDataStore();
        /*
        HostVO secondaryStorageHost = this.templateMgr
                .getSecondaryStorageHost(zoneId);
                */
        String secondaryStorageURL = snapshotMgr
                .getSecondaryStorageURL(snapshot);
        VMTemplateVO template = this.templateDao.findById(destData.getId());
        String name = template.getName();
        String backupSnapshotUUID = snapshot.getBackupSnapshotId();
        if (backupSnapshotUUID == null) {
            throw new CloudRuntimeException(
                    "Unable to create private template from snapshot "
                            + snapshotId
                            + " due to there is no backupSnapshotUUID for this snapshot");
        }

        Long dcId = snapshot.getDataCenterId();
        Long accountId = snapshot.getAccountId();
        Long volumeId = snapshot.getVolumeId();

        String origTemplateInstallPath = null;
        List<StoragePoolVO> pools = this.storageMgr
                .ListByDataCenterHypervisor(zoneId,
                        snapshot.getHypervisorType());
        if (pools == null || pools.size() == 0) {
            throw new CloudRuntimeException(
                    "Unable to find storage pools in zone " + zoneId);
        }
        StoragePoolVO poolvo = pools.get(0);
        StoragePool pool = (StoragePool) this.dataStoreMgr.getDataStore(
                poolvo.getId(), DataStoreRole.Primary);
        if (snapshot.getVersion() != null
                && snapshot.getVersion().equalsIgnoreCase("2.1")) {
            VolumeVO volume = this.volDao.findByIdIncludingRemoved(volumeId);
            if (volume == null) {
                throw new CloudRuntimeException("failed to upgrade snapshot "
                        + snapshotId + " due to unable to find orignal volume:"
                        + volumeId + ", try it later ");
            }
            if (volume.getTemplateId() == null) {
                snapshotDao.updateSnapshotVersion(volumeId, "2.1", "2.2");
            } else {
                template = templateDao.findByIdIncludingRemoved(volume
                        .getTemplateId());
                if (template == null) {
                    throw new CloudRuntimeException(
                            "failed to upgrade snapshot "
                                    + snapshotId
                                    + " due to unalbe to find orignal template :"
                                    + volume.getTemplateId()
                                    + ", try it later ");
                }
                Long origTemplateId = template.getId();
                Long origTmpltAccountId = template.getAccountId();
                if (!this.volDao.lockInLockTable(volumeId.toString(), 10)) {
                    throw new CloudRuntimeException(
                            "failed to upgrade snapshot " + snapshotId
                                    + " due to volume:" + volumeId
                                    + " is being used, try it later ");
                }
                UpgradeSnapshotCommand cmd = new UpgradeSnapshotCommand(null,
                        secondaryStorageURL, dcId, accountId, volumeId,
                        origTemplateId, origTmpltAccountId, null,
                        snapshot.getBackupSnapshotId(), snapshot.getName(),
                        "2.1");
                if (!this.volDao.lockInLockTable(volumeId.toString(), 10)) {
                    throw new CloudRuntimeException(
                            "Creating template failed due to volume:"
                                    + volumeId
                                    + " is being used, try it later ");
                }
                Answer answer = null;
                try {
                    answer = this.storageMgr.sendToPool(pool, cmd);
                    cmd = null;
                } catch (StorageUnavailableException e) {
                } finally {
                    this.volDao.unlockFromLockTable(volumeId.toString());
                }
                if ((answer != null) && answer.getResult()) {
                    snapshotDao.updateSnapshotVersion(volumeId, "2.1", "2.2");
                } else {
                    throw new CloudRuntimeException(
                            "Unable to upgrade snapshot");
                }
            }
        }
        if (snapshot.getSwiftId() != null && snapshot.getSwiftId() != 0) {
            snapshotMgr.downloadSnapshotsFromSwift(snapshot);
        }
        String value = configDao
                .getValue(Config.CreatePrivateTemplateFromSnapshotWait
                        .toString());
        int _createprivatetemplatefromsnapshotwait = NumbersUtil.parseInt(
                value, Integer
                        .parseInt(Config.CreatePrivateTemplateFromSnapshotWait
                                .getDefaultValue()));

        CreatePrivateTemplateFromSnapshotCommand cmd = new CreatePrivateTemplateFromSnapshotCommand(
                pool, secondaryStorageURL, dcId, accountId,
                snapshot.getVolumeId(), backupSnapshotUUID, snapshot.getName(),
                origTemplateInstallPath, template.getId(), name,
                _createprivatetemplatefromsnapshotwait);

        return sendCommand(cmd, pool, template.getId(), dcId, secStore);
    }

    @DB
    protected Answer sendCommand(Command cmd, StoragePool pool,
            long templateId, long zoneId, DataStore secStore) {

        CreatePrivateTemplateAnswer answer = null;
        try {
            answer = (CreatePrivateTemplateAnswer) this.storageMgr.sendToPool(
                    pool, cmd);
        } catch (StorageUnavailableException e) {
            throw new CloudRuntimeException(
                    "Failed to execute CreatePrivateTemplateFromSnapshotCommand",
                    e);
        }

        if (answer == null || !answer.getResult()) {
        	return answer;
        }

        VMTemplateVO privateTemplate = templateDao.findById(templateId);
        String answerUniqueName = answer.getUniqueName();
        if (answerUniqueName != null) {
            privateTemplate.setUniqueName(answerUniqueName);
        }
        ImageFormat format = answer.getImageFormat();
        if (format != null) {
            privateTemplate.setFormat(format);
        } else {
            // This never occurs.
            // Specify RAW format makes it unusable for snapshots.
            privateTemplate.setFormat(ImageFormat.RAW);
        }

        String checkSum = this.templateMgr
                .getChecksum(secStore, answer.getPath());

        Transaction txn = Transaction.currentTxn();

        txn.start();

        privateTemplate.setChecksum(checkSum);
        templateDao.update(privateTemplate.getId(), privateTemplate);

        // add template zone ref for this template
        templateDao.addTemplateToZone(privateTemplate, zoneId);
        TemplateDataStoreVO templateHostVO = new TemplateDataStoreVO(secStore.getId(),
                privateTemplate.getId());
        templateHostVO.setDownloadPercent(100);
        templateHostVO.setDownloadState(Status.DOWNLOADED);
        templateHostVO.setInstallPath(answer.getPath());
        templateHostVO.setLastUpdated(new Date());
        templateHostVO.setSize(answer.getVirtualSize());
        templateHostVO.setPhysicalSize(answer.getphysicalSize());
        templateStoreDao.persist(templateHostVO);
        txn.close();
        return answer;
    }

    private Answer createTemplateFromVolume(DataObject srcObj,
            DataObject destObj) {
        long volumeId = srcObj.getId();
        VolumeVO volume = this.volDao.findById(volumeId);
        if (volume == null) {
            throw new CloudRuntimeException("Unable to find volume for Id "
                    + volumeId);
        }
        long accountId = volume.getAccountId();

        String vmName = this.volumeMgr.getVmNameOnVolume(volume);
        Long zoneId = volume.getDataCenterId();
        DataStore secStore = destObj.getDataStore();
        String secondaryStorageURL = secStore.getUri();
        VMTemplateVO template = this.templateDao.findById(destObj.getId());
        StoragePool pool = (StoragePool) this.dataStoreMgr.getDataStore(
                volume.getPoolId(), DataStoreRole.Primary);
        String value = configDao
                .getValue(Config.CreatePrivateTemplateFromVolumeWait.toString());
        int _createprivatetemplatefromvolumewait = NumbersUtil.parseInt(value,
                Integer.parseInt(Config.CreatePrivateTemplateFromVolumeWait
                        .getDefaultValue()));

        CreatePrivateTemplateFromVolumeCommand cmd = new CreatePrivateTemplateFromVolumeCommand(
                pool, secondaryStorageURL, destObj.getId(), accountId,
                template.getName(), template.getUniqueName(), volume.getPath(),
                vmName, _createprivatetemplatefromvolumewait);

        return sendCommand(cmd, pool, template.getId(), zoneId, secStore);
    }

    private DataStore getSecHost(long volumeId, long dcId) {
        Long id = snapshotDao.getSecHostId(volumeId);
        if ( id != null) {
            return this.dataStoreMgr.getDataStore(id, DataStoreRole.Image);
        }
        return this.dataStoreMgr.getImageStore(dcId);
    }

    protected Answer copySnapshot(DataObject srcObject, DataObject destObject) {
    	SnapshotInfo srcSnapshot = (SnapshotInfo)srcObject;
    	VolumeInfo baseVolume = srcSnapshot.getBaseVolume();
    	 Long dcId = baseVolume.getDataCenterId();
         Long accountId = baseVolume.getAccountId();

         DataStore secStore = getSecHost(baseVolume.getId(), baseVolume.getDataCenterId());
         Long secHostId = secStore.getId();
         String secondaryStoragePoolUrl = secStore.getUri();
         String snapshotUuid = srcSnapshot.getPath();
         // In order to verify that the snapshot is not empty,
         // we check if the parent of the snapshot is not the same as the parent of the previous snapshot.
         // We pass the uuid of the previous snapshot to the plugin to verify this.
         SnapshotVO prevSnapshot = null;
         String prevSnapshotUuid = null;
         String prevBackupUuid = null;


         SwiftTO swift = _swiftMgr.getSwiftTO();
         S3TO s3 = _s3Mgr.getS3TO();

         long prevSnapshotId = srcSnapshot.getPrevSnapshotId();
         if (prevSnapshotId > 0) {
             prevSnapshot = snapshotDao.findByIdIncludingRemoved(prevSnapshotId);
             if ( prevSnapshot.getBackupSnapshotId() != null && swift == null) {
                 if (prevSnapshot.getVersion() != null && prevSnapshot.getVersion().equals("2.2")) {
                     prevBackupUuid = prevSnapshot.getBackupSnapshotId();
                     prevSnapshotUuid = prevSnapshot.getPath();
                 }
             } else if ((prevSnapshot.getSwiftId() != null && swift != null)
                     || (prevSnapshot.getS3Id() != null && s3 != null)) {
                 prevBackupUuid = prevSnapshot.getBackupSnapshotId();
                 prevSnapshotUuid = prevSnapshot.getPath();
             }
         }
         boolean isVolumeInactive = this.volumeMgr.volumeInactive(baseVolume);
         String vmName = this.volumeMgr.getVmNameOnVolume(baseVolume);
         StoragePool srcPool = (StoragePool)dataStoreMgr.getPrimaryDataStore(baseVolume.getPoolId());
         String value = configDao.getValue(Config.BackupSnapshotWait.toString());
         int _backupsnapshotwait = NumbersUtil.parseInt(value, Integer.parseInt(Config.BackupSnapshotWait.getDefaultValue()));
         BackupSnapshotCommand backupSnapshotCommand = new BackupSnapshotCommand(secondaryStoragePoolUrl, dcId, accountId, baseVolume.getId(), srcSnapshot.getId(), secHostId, baseVolume.getPath(), srcPool, snapshotUuid,
        		 srcSnapshot.getName(), prevSnapshotUuid, prevBackupUuid, isVolumeInactive, vmName, _backupsnapshotwait);

         if ( swift != null ) {
             backupSnapshotCommand.setSwift(swift);
         } else if (s3 != null) {
             backupSnapshotCommand.setS3(s3);
         }
         BackupSnapshotAnswer answer = (BackupSnapshotAnswer) this.snapshotMgr.sendToPool(baseVolume, backupSnapshotCommand);
         if (answer != null && answer.getResult()) {
        	 SnapshotVO snapshotVO = this.snapshotDao.findById(srcSnapshot.getId());
        	 snapshotVO.setBackupSnapshotId(answer.getBackupSnapshotName());
        	 // persist an entry in snapshot_store_ref
        	 SnapshotDataStoreVO snapshotStore = new SnapshotDataStoreVO(secStore.getId(), snapshotVO.getId());
        	 this._snapshotStoreDao.persist(snapshotStore);
 			if (answer.isFull()) {
 				snapshotVO.setPrevSnapshotId(0L);
			}
        	 this.snapshotDao.update(srcSnapshot.getId(), snapshotVO);
         }
         return answer;
    }

}
