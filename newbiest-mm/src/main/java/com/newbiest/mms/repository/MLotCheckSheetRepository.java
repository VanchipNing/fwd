package com.newbiest.mms.repository;

import com.newbiest.base.exception.ClientException;
import com.newbiest.base.repository.custom.IRepository;
import com.newbiest.mms.model.MLotCheckSheet;
import org.springframework.stereotype.Repository;


@Repository
public interface MLotCheckSheetRepository extends IRepository<MLotCheckSheet, String> {

    MLotCheckSheet findByMaterialLotIdAndStatus(String materialLotId, String status) throws ClientException;

}
