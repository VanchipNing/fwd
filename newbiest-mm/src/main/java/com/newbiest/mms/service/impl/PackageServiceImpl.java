package com.newbiest.mms.service.impl;

import com.google.common.collect.Lists;
import com.newbiest.base.exception.ClientException;
import com.newbiest.base.exception.ClientParameterException;
import com.newbiest.base.exception.ExceptionManager;
import com.newbiest.base.model.NBHis;
import com.newbiest.base.service.BaseService;
import com.newbiest.base.utils.CollectionUtils;
import com.newbiest.base.utils.SessionContext;
import com.newbiest.base.utils.StringUtils;
import com.newbiest.base.utils.ThreadLocalContext;
import com.newbiest.common.idgenerator.service.GeneratorService;
import com.newbiest.common.idgenerator.utils.GeneratorContext;
import com.newbiest.mms.SystemPropertyUtils;
import com.newbiest.mms.dto.MaterialLotAction;
import com.newbiest.mms.exception.MmsException;
import com.newbiest.mms.model.*;
import com.newbiest.mms.repository.*;
import com.newbiest.mms.service.MmsService;
import com.newbiest.mms.service.PackageService;
import com.newbiest.mms.state.model.MaterialEvent;
import com.newbiest.mms.state.model.MaterialStatus;
import com.newbiest.mms.state.model.MaterialStatusCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by guoxunbo on 2019/4/1.
 */
@Service
@Slf4j
@Transactional
public class PackageServiceImpl implements PackageService{

    @Autowired
    MaterialLotPackageTypeRepository materialLotPackageTypeRepository;

    @Autowired
    MaterialLotHistoryRepository materialLotHistoryRepository;

    @Autowired
    PackagedLotDetailRepository packagedLotDetailRepository;

    @Autowired
    MaterialLotRepository materialLotRepository;

    @Autowired
    GeneratorService generatorService;

    @Autowired
    MmsService mmsService;

    @Autowired
    BaseService baseService;

    @Autowired
    MaterialLotInventoryRepository materialLotInventoryRepository;

    public MaterialLotPackageType getMaterialPackageTypeByName(String name) {
        List<MaterialLotPackageType> packageTypes = (List<MaterialLotPackageType>) materialLotPackageTypeRepository.findByNameAndOrgRrn(name, ThreadLocalContext.getOrgRrn());
        if (CollectionUtils.isNotEmpty(packageTypes)) {
            return packageTypes.get(0);
        } else {
            throw new ClientParameterException(MmsException.MM_PACKAGE_TYPE_IS_NOT_EXIST, name);
        }
    }

    /**
     * 获取包装详细信息
     * @return
     */
    public List<MaterialLot> getPackageDetailLots(Long packagedLotRrn) {
        try {
            return materialLotRepository.getPackageDetailLots(packagedLotRrn);
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 追加包装
     *   当前不卡控被追加包装批次的状态
     * @param packedMaterialLot 被追加的包装批次
     * @param materialLotActions 包装批次动作
     * @return
     */
    public MaterialLot appendPacking(MaterialLot packedMaterialLot, List<MaterialLotAction> materialLotActions) throws ClientException {
        try {
            SessionContext sc = ThreadLocalContext.getSessionContext();
            sc.buildTransInfo();

            packedMaterialLot = mmsService.getMLotByMLotId(packedMaterialLot.getMaterialLotId(), true);
            packedMaterialLot.isFinish();
            // 取第一个的materialAction作为所有者的actionCode
            MaterialLotAction firstMaterialAction = materialLotActions.get(0);
            List<MaterialLot> allMaterialLot = Lists.newArrayList();

            List<MaterialLot> waitToAddPackingMLots = materialLotActions.stream().map(action -> mmsService.getMLotByMLotId(action.getMaterialLotId())).collect(Collectors.toList());
            allMaterialLot.addAll(waitToAddPackingMLots);
            // 取到包装规则
            MaterialLotPackageType materialLotPackageType = getMaterialPackageTypeByName(packedMaterialLot.getPackageType());

            // 将包装的和以前包装的放在一起进行包装规则验证
            List<MaterialLot> packageDetailLots = getPackageDetailLots(packedMaterialLot.getObjectRrn());

            List<MaterialLotAction> allMaterialAction = Lists.newArrayList(materialLotActions);
            if (CollectionUtils.isNotEmpty(packageDetailLots)) {
                allMaterialLot.addAll(packageDetailLots);
                //TODO 此处因为此版本中项目要求不扣减批次数量。所有直接使用批次上的CurrentQty。后续版本需要使用detail上的数据
                for (MaterialLot packageDetailLot : packageDetailLots) {
                    MaterialLotAction packedMLotAction = new MaterialLotAction();
                    packedMLotAction.setMaterialLotId(packageDetailLot.getMaterialLotId());
                    packedMLotAction.setTransQty(packageDetailLot.getCurrentQty());
                    allMaterialAction.add(packedMLotAction);
                }
            }
            materialLotPackageType.validationAppendPacking(waitToAddPackingMLots, allMaterialAction);

            packedMaterialLot.setCurrentQty(materialLotPackageType.getPackedQty(allMaterialAction));
            packedMaterialLot = materialLotRepository.saveAndFlush(packedMaterialLot);
//            // 记录创建历史
            MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(packedMaterialLot, MaterialLotHistory.TRANS_TYPE_ADDITIONAL_PACKAGE);
            history.buildByMaterialLotAction(firstMaterialAction);
            history.setActionCode(firstMaterialAction.getActionCode());
            history.setActionReason(firstMaterialAction.getActionReason());
            materialLotHistoryRepository.save(history);

            packageMaterialLots(packedMaterialLot, waitToAddPackingMLots, materialLotActions, false, true);
            return packedMaterialLot;
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 拆包装
     *
     * @param materialLotActions 需要被拆出来的物料批次
     * @throws ClientException
     */
    public void unPack(List<MaterialLotAction> materialLotActions) throws ClientException {
        try {
            SessionContext sc = ThreadLocalContext.getSessionContext();
            sc.buildTransInfo();
            //因为当前仅支持全部包装。故此处，直接用ParentMaterialLotId做包装号。
            Map<String, List<MaterialLot>> packedLotMap = materialLotActions.stream().map(materialLotAction -> mmsService.getMLotByMLotId(materialLotAction.getMaterialLotId(), true))
                    .collect(Collectors.groupingBy(MaterialLot::getParentMaterialLotId));
            for (String packageMLotId : packedLotMap.keySet()) {
                MaterialLot packagedLot = mmsService.getMLotByMLotId(packageMLotId, true);
                unPack(packagedLot, packedLotMap.get(packageMLotId), materialLotActions);
            }
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 拆包装
     * @param packedMaterialLot 待拆包装的包装批次
     * @param waitToUnPackageMLots 待拆出来的包装批次
     * @param materialLotActions 待拆出来的包装批次的动作。拆包数量以action中的数量为准
     * @throws ClientException
     */
    public void unPack(MaterialLot packedMaterialLot, List<MaterialLot> waitToUnPackageMLots, List<MaterialLotAction> materialLotActions) throws ClientException{
        try {
            SessionContext sc = ThreadLocalContext.getSessionContext();
            sc.buildTransInfo();
            // 取到包装规则
            MaterialLotPackageType materialLotPackageType = getMaterialPackageTypeByName(packedMaterialLot.getPackageType());
            BigDecimal packedQty = materialLotPackageType.getPackedQty(materialLotActions);

            packedMaterialLot.setCurrentQty(packedMaterialLot.getCurrentQty().subtract(packedQty));
            if (packedMaterialLot.getCurrentQty().compareTo(BigDecimal.ZERO) == 0) {
                packedMaterialLot = mmsService.changeMaterialLotState(packedMaterialLot, MaterialEvent.EVENT_UN_PACKAGE, StringUtils.EMPTY);
                packedMaterialLot = materialLotRepository.saveAndFlush(packedMaterialLot);
            }
            // 扣减库存 箱批次只会存在一个位置上
            List<MaterialLotInventory> materialLotInventories = mmsService.getMaterialLotInv(packedMaterialLot.getObjectRrn());
            if (CollectionUtils.isNotEmpty(materialLotInventories)) {
                MaterialLotInventory materialLotInventory = materialLotInventories.get(0);
                mmsService.saveMaterialLotInventory(materialLotInventory, packedQty.negate());
            }

            Map<String, MaterialLotAction> materialLotActionMap = materialLotActions.stream().collect(Collectors.toMap(MaterialLotAction :: getMaterialLotId, Function.identity()));

            Map<String, PackagedLotDetail> packagedLotDetails = packagedLotDetailRepository.findByPackagedLotRrn(packedMaterialLot.getObjectRrn()).stream().collect(Collectors.toMap(PackagedLotDetail :: getMaterialLotId, Function.identity()));
            for (MaterialLot waitToUnPackageMLot : waitToUnPackageMLots) {
                MaterialLotAction materialLotAction = materialLotActionMap.get(waitToUnPackageMLot.getMaterialLotId());

                // 更新packageDetail数量
                PackagedLotDetail packagedLotDetail = packagedLotDetails.get(waitToUnPackageMLot.getMaterialLotId());
                packagedLotDetail.setQty(packagedLotDetail.getQty().subtract(materialLotAction.getTransQty()));
                if (packagedLotDetail.getQty().compareTo(BigDecimal.ZERO) == 0) {
                    packagedLotDetailRepository.deleteById(packagedLotDetail.getObjectRrn());
                } else {
                    packagedLotDetailRepository.save(packagedLotDetail);
                }
                // 根据JVM参数来判断是否要直接还原被包装的批次数量
                if (SystemPropertyUtils.getUnpackRecoveryLotFlag()) {
                    waitToUnPackageMLot.setParentMaterialLotRrn(null);
                    waitToUnPackageMLot.setParentMaterialLotId(StringUtils.EMPTY);
                    waitToUnPackageMLot.restoreStatus();
                    materialLotRepository.save(waitToUnPackageMLot);
                    MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(waitToUnPackageMLot, MaterialLotHistory.TRANS_TYPE_UN_PACKAGE);
                    history.buildByMaterialLotAction(materialLotAction);

                    if (MaterialStatusCategory.STATUS_CATEGORY_STOCK.equals(waitToUnPackageMLot.getStatusCategory()) && MaterialStatus.STATUS_IN.equals(waitToUnPackageMLot.getStatus())) {
                        // 找到最后一笔包装数据
                        MaterialLotHistory materialLotHistory = materialLotHistoryRepository.findTopByMaterialLotIdAndTransTypeOrderByCreatedDesc(waitToUnPackageMLot.getMaterialLotId(), MaterialLotHistory.TRANS_TYPE_PACKAGE);
                        if (materialLotHistory != null) {
                            Warehouse warehouse = mmsService.getWarehouseByName(materialLotHistory.getTransWarehouseId());
                            Storage storage = mmsService.getStorageByWarehouseRrnAndName(warehouse, materialLotHistory.getTransStorageId());
                            // 恢复库存数据
                            MaterialLotInventory materialLotInventory = new MaterialLotInventory();
                            materialLotInventory.setMaterialLot(waitToUnPackageMLot).setWarehouse(warehouse).setStorage(storage);
                            mmsService.saveMaterialLotInventory(materialLotInventory, waitToUnPackageMLot.getCurrentQty());

                            history.setTargetWarehouseId(warehouse.getName());
                            history.setTransStorageId(storage.getName());
                        }
                    }
                    materialLotHistoryRepository.save(history);

                }
            }
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 对物料批次进行包装
     * @return
     */
    public MaterialLot packageMLots(List<MaterialLotAction> materialLotActions, String packageType) throws ClientException{
        try {
            SessionContext sc = ThreadLocalContext.getSessionContext();
            sc.buildTransInfo();
            // 取第一个的materialAction作为所有者的actionCode
            MaterialLotAction firstMaterialAction = materialLotActions.get(0);

            List<MaterialLot> materialLots = materialLotActions.stream().map(action -> mmsService.getMLotByMLotId(action.getMaterialLotId())).collect(Collectors.toList());
            MaterialLotPackageType materialLotPackageType = getMaterialPackageTypeByName(packageType);
            materialLotPackageType.validationPacking(materialLots);

            if (!StringUtils.isNullOrEmpty(materialLotPackageType.getMergeRule())) {
                mmsService.validationMergeRule(materialLotPackageType.getMergeRule(), materialLots);
            }

            MaterialLot packedMaterialLot = (MaterialLot) materialLots.get(0).clone();
            String packedMaterialLotId = generatorPackageMLotId(packedMaterialLot, materialLotPackageType);
            if (mmsService.getMLotByMLotId(packedMaterialLotId) != null) {
                throw new ClientException(MmsException.MM_MATERIAL_LOT_IS_EXIST);
            }
            packedMaterialLot.setMaterialLotId(packedMaterialLotId);
            packedMaterialLot.setCurrentQty(materialLotPackageType.getPackedQty(materialLotActions));
            packedMaterialLot.setReceiveQty(packedMaterialLot.getCurrentQty());
            packedMaterialLot.initialMaterialLot();

            packedMaterialLot.setStatusCategory(MaterialStatusCategory.STATUS_CATEGORY_USE);
            packedMaterialLot.setStatus(MaterialStatus.STATUS_WAIT);
            packedMaterialLot.setPackageType(packageType);

            packedMaterialLot.setMaterialType(StringUtils.isNullOrEmpty(materialLotPackageType.getTargetMaterialType()) ? packedMaterialLot.getMaterialType() : materialLotPackageType.getTargetMaterialType());
            packedMaterialLot = materialLotRepository.saveAndFlush(packedMaterialLot);

            // 记录创建历史
            MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(packedMaterialLot, MaterialLotHistory.TRANS_TYPE_CREATE_PACKAGE);
            history.buildByMaterialLotAction(firstMaterialAction);
            materialLotHistoryRepository.save(history);

            packageMaterialLots(packedMaterialLot, materialLots, materialLotActions, false, true);
            return packedMaterialLot;
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 批次包装
     * @param packedMaterialLot
     * @param waitToPackingLot
     * @param materialLotActions
     * @param subtractQtyFlag 是否扣减被包装批次的数量 后续版本去除此参数 根据物料属性自己判断
     * @param updateParentMLotFlag 是否更新包装批次的信息为被包装信息的parent相关栏位 后续版本去除此参数 根据物料属性自己判断
     */
    private void packageMaterialLots(MaterialLot packedMaterialLot, List<MaterialLot> waitToPackingLot, List<MaterialLotAction> materialLotActions,
                                        boolean subtractQtyFlag, boolean updateParentMLotFlag) throws ClientException {
        // 对物料批次做package事件处理 扣减物料批次数量
        for (MaterialLot materialLot : waitToPackingLot) {

            //TODO 此处为GC客制化 清除中转箱号 后续公共版本需要清除此代码
            materialLot.setReserved8(StringUtils.EMPTY);
            String materialLotId = materialLot.getMaterialLotId();
            MaterialLotAction materialLotAction = materialLotActions.stream().filter(action -> materialLotId.equals(action.getMaterialLotId())).findFirst().get();

            BigDecimal currentQty = materialLot.getCurrentQty();

            if (subtractQtyFlag) {
                materialLot.setCurrentQty(materialLot.getCurrentQty().subtract(materialLotAction.getTransQty()));
            }
            if (currentQty.compareTo(materialLotAction.getTransQty()) == 0) {
                if (updateParentMLotFlag) {
                    materialLot.setParentMaterialLotId(packedMaterialLot.getMaterialLotId());
                    materialLot.setParentMaterialLotRrn(packedMaterialLot.getObjectRrn());
                }
                materialLot = mmsService.changeMaterialLotState(materialLot, MaterialEvent.EVENT_PACKAGE, MaterialStatus.STATUS_PACKED);
            }
            // 记录历史
            MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, MaterialLotHistory.TRANS_TYPE_PACKAGE);

            // 如果批次在库存中，则直接消耗库存数量 支持一个批次在多个仓库中 故这里直接取第一个库存位置消耗
            // 不考虑subtractQtyFlag因素，都消耗库存
            List<MaterialLotInventory> materialLotInventories = mmsService.getMaterialLotInv(materialLot.getObjectRrn());
            if (CollectionUtils.isNotEmpty(materialLotInventories)) {
                MaterialLotInventory materialLotInventory = materialLotInventories.get(0);
                mmsService.saveMaterialLotInventory(materialLotInventory, materialLotAction.getTransQty().negate());

                history.setTransWarehouseId(materialLotInventory.getWarehouseId());
                history.setTransStorageId(materialLotInventory.getStorageId());
                history.setTransStorageType(materialLotInventory.getStorageType());
            }

            history.setActionCode(materialLotAction.getActionCode());
            history.setActionReason(materialLotAction.getActionReason());
            history.setActionComment(materialLotAction.getActionComment());
            materialLotHistoryRepository.save(history);

            // 记录包装详情
            PackagedLotDetail packagedLotDetail = packagedLotDetailRepository.findByPackagedLotRrnAndMaterialLotRrn(packedMaterialLot.getObjectRrn(), materialLot.getObjectRrn());
            if (packagedLotDetail == null) {
                packagedLotDetail = new PackagedLotDetail();
                packagedLotDetail.setPackagedLotRrn(packedMaterialLot.getObjectRrn());
                packagedLotDetail.setPackagedLotId(packedMaterialLot.getMaterialLotId());
                packagedLotDetail.setMaterialLotRrn(materialLot.getObjectRrn());
                packagedLotDetail.setMaterialLotId(materialLot.getMaterialLotId());
            }
            packagedLotDetail.setQty(packagedLotDetail.getQty().add(materialLotAction.getTransQty()));
            packagedLotDetailRepository.save(packagedLotDetail);
        }

    }

    /**
     * 生成打包之后的物料批次号
     * @return
     * @throws ClientException
     */
    public String generatorPackageMLotId(MaterialLot packageMaterialLot, MaterialLotPackageType packageType) throws ClientException{
        try {
            GeneratorContext generatorContext = new GeneratorContext();
            generatorContext.setObject(packageMaterialLot);
            generatorContext.setRuleName(packageType.getPackIdRule());
            return generatorService.generatorId(ThreadLocalContext.getOrgRrn(), generatorContext);
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }
}
