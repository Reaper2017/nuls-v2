package io.nuls.transaction.service.impl;

import io.nuls.base.basic.AddressTool;
import io.nuls.base.constant.TxStatusEnum;
import io.nuls.base.data.*;
import io.nuls.rpc.util.RPCUtil;
import io.nuls.rpc.util.TimeUtils;
import io.nuls.tools.core.annotation.Autowired;
import io.nuls.tools.core.annotation.Component;
import io.nuls.tools.exception.NulsException;
import io.nuls.transaction.cache.PackablePool;
import io.nuls.transaction.constant.TxConfig;
import io.nuls.transaction.constant.TxConstant;
import io.nuls.transaction.constant.TxErrorCode;
import io.nuls.transaction.manager.ChainManager;
import io.nuls.transaction.manager.TxManager;
import io.nuls.transaction.model.bo.Chain;
import io.nuls.transaction.model.bo.TxRegister;
import io.nuls.transaction.model.po.TransactionConfirmedPO;
import io.nuls.transaction.rpc.call.LedgerCall;
import io.nuls.transaction.rpc.call.NetworkCall;
import io.nuls.transaction.rpc.call.TransactionCall;
import io.nuls.transaction.service.ConfirmedTxService;
import io.nuls.transaction.service.CtxService;
import io.nuls.transaction.service.TxService;
import io.nuls.transaction.storage.rocksdb.ConfirmedTxStorageService;
import io.nuls.transaction.storage.rocksdb.CtxStorageService;
import io.nuls.transaction.storage.rocksdb.UnconfirmedTxStorageService;
import io.nuls.transaction.utils.TxUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nuls.transaction.utils.LoggerUtil.Log;

/**
 * @author: Charlie
 * @date: 2018/11/30
 */
@Component
public class ConfirmedTxServiceImpl implements ConfirmedTxService {

    @Autowired
    private ConfirmedTxStorageService confirmedTxStorageService;

    @Autowired
    private UnconfirmedTxStorageService unconfirmedTxStorageService;

    @Autowired
    private ChainManager chainManager;

    @Autowired
    private CtxStorageService ctxStorageService;

    @Autowired
    private PackablePool packablePool;

    @Autowired
    private CtxService ctxService;

    @Autowired
    private TxService txService;

    @Autowired
    private TxConfig txConfig;

    @Override
    public TransactionConfirmedPO getConfirmedTransaction(Chain chain, NulsDigestData hash) {
        if (null == hash) {
            return null;
        }
        return confirmedTxStorageService.getTx(chain.getChainId(), hash);
    }

    @Override
    public boolean saveGengsisTxList(Chain chain, List<Transaction> txList, String blockHeader) throws NulsException {
        if (null == chain || txList == null || txList.size() == 0) {
            throw new NulsException(TxErrorCode.PARAMETER_ERROR);
        }
        if (!saveBlockTxList(chain, txList, blockHeader, true)) {
            chain.getLoggerMap().get(TxConstant.LOG_TX).debug("保存创世块交易失败");
            return false;
        }
        CoinData coinData = TxUtil.getCoinData(txList.get(0));
        for (Coin coin : coinData.getTo()) {
            chain.getLoggerMap().get(TxConstant.LOG_TX).debug("address:{}, to:{}", AddressTool.getStringAddressByBytes(coin.getAddress()), coin.getAmount());
        }

        chain.getLoggerMap().get(TxConstant.LOG_TX).debug("保存创世块交易成功");
        return true;
    }

    /**
     * 1.保存交易
     * 2.调提交易接口
     * 3.调账本
     * 4.从未打包交易库中删除交易
     */
    @Override
    public boolean saveTxList(Chain chain, List<NulsDigestData> txHashList, String blockHeader) throws NulsException {
        chain.getLoggerMap().get(TxConstant.LOG_TX).debug("start save block txs.......");
        if (null == chain || txHashList == null || txHashList.size() == 0) {
            throw new NulsException(TxErrorCode.PARAMETER_ERROR);
        }
        try {
            List<Transaction> txList = new ArrayList<>();
            for (int i = 0; i < txHashList.size(); i++) {
                NulsDigestData hash = txHashList.get(i);
                Transaction tx = unconfirmedTxStorageService.getTx(chain.getChainId(), hash);
                txList.add(tx);
            }
            return saveBlockTxList(chain, txList, blockHeader, false);
        } catch (Exception e) {
            chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
            return false;
        }
    }

    private boolean saveBlockTxList(Chain chain, List<Transaction> txList, String blockHeaderStr, boolean gengsis) throws NulsException {
        long start = TimeUtils.getCurrentTimeMillis();//-----
        List<String> txStrList = new ArrayList<>();
        int chainId = chain.getChainId();
        List<byte[]> txHashs = new ArrayList<>();
        //组装统一验证参数数据,key为各模块统一验证器cmd
        Map<TxRegister, List<String>> moduleVerifyMap = new HashMap<>(TxConstant.INIT_CAPACITY_8);
        BlockHeader blockHeader = null;
        try {
            blockHeader = TxUtil.getInstanceRpcStr(blockHeaderStr, BlockHeader.class);
            Log.debug("[保存区块] ==========开始==========高度:{}==========数量:{}", blockHeader.getHeight(), txList.size());//----
            chain.getLoggerMap().get(TxConstant.LOG_TX).debug("saveBlockTxList block height:{}", blockHeader.getHeight());
            for (Transaction tx : txList) {
                tx.setBlockHeight(blockHeader.getHeight());
                String txStr = RPCUtil.encode(tx.serialize());
                txStrList.add(txStr);
                txHashs.add(tx.getHash().serialize());
                if(TxManager.isSystemSmartContract(chain, tx.getType())) {
                    continue;
                }
                TxUtil.moduleGroups(chain, moduleVerifyMap, tx);
            }
        } catch (Exception e) {
            chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
            return false;
        }
        Log.debug("[保存区块] 组装数据 执行时间:{}", TimeUtils.getCurrentTimeMillis() - start);//----
        Log.debug("");//----

        long dbStart = TimeUtils.getCurrentTimeMillis();//-----
        if (!saveTxs(chain, txList, blockHeader.getHeight(), true)) {
            return false;
        }
        Log.debug("[保存区块] 存已确认交易DB 执行时间:{}", TimeUtils.getCurrentTimeMillis()- dbStart);//----
        Log.debug("");//----

        long commitStart = TimeUtils.getCurrentTimeMillis();//-----
        if (!gengsis && !commitTxs(chain, moduleVerifyMap, blockHeaderStr, true)) {
            removeTxs(chain, txList, blockHeader.getHeight(), false);
            return false;
        }
        Log.debug("[保存区块] 交易业务提交 执行时间:{}", TimeUtils.getCurrentTimeMillis() - commitStart);//----
        Log.debug("");//----

        long ledgerStart = TimeUtils.getCurrentTimeMillis();//-----
        if (!commitLedger(chain, txStrList, blockHeader.getHeight())) {
            if (!gengsis) {
                rollbackTxs(chain, moduleVerifyMap, blockHeaderStr, false);
            }
            removeTxs(chain, txList, blockHeader.getHeight(), false);
            return false;
        }
        Log.debug("[保存区块] 账本模块提交 执行时间:{}", TimeUtils.getCurrentTimeMillis() - ledgerStart);//----
        Log.debug("");//----
        //如果确认交易成功，则从未打包交易库中删除交易
        unconfirmedTxStorageService.removeTxList(chainId, txHashs);
        Log.debug("[保存区块] ======/========/结束======/========/合计执行时间:{}", TimeUtils.getCurrentTimeMillis() - start);//----
        Log.debug("");//----
        chain.getLoggerMap().get(TxConstant.LOG_TX).debug("save block Txs success! height:{}, txSize:{}", blockHeader.getHeight(), txList.size());
        return true;
    }


    /**保存交易*/
    private boolean saveTxs(Chain chain, List<Transaction> txList, long blockHeight, boolean atomicity) {
        boolean rs = true;
        List<TransactionConfirmedPO> toSaveList = new ArrayList<>();
        for (Transaction tx : txList) {
            tx.setStatus(TxStatusEnum.CONFIRMED);
            TransactionConfirmedPO txConfirmedPO = new TransactionConfirmedPO(tx, blockHeight, TxStatusEnum.CONFIRMED.getStatus());
            toSaveList.add(txConfirmedPO);
        }
        if(!confirmedTxStorageService.saveTxList(chain.getChainId(), toSaveList)){
            if (atomicity) {
                removeTxs(chain, txList, blockHeight, false);
            }
            rs = false;
            chain.getLoggerMap().get(TxConstant.LOG_TX).debug("save block Txs rocksdb failed! ");
        }
        return rs;
    }

    /**调提交易*/
    private boolean commitTxs(Chain chain, Map<TxRegister, List<String>> moduleVerifyMap, String blockHeader, boolean atomicity) {
        //调用交易模块统一commit接口 批量
        Map<TxRegister, List<String>> successed = new HashMap<>(TxConstant.INIT_CAPACITY_8);
        boolean result = true;
        for (Map.Entry<TxRegister, List<String>> entry : moduleVerifyMap.entrySet()) {
            boolean rs;
            if (entry.getKey().getModuleCode().equals(txConfig.getModuleCode())) {
                try {
                    rs = txService.crossTransactionCommit(chain, entry.getValue(), blockHeader);
                } catch (NulsException e) {
                    chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
                    rs = false;
                }
            } else {
                rs = TransactionCall.txProcess(chain, entry.getKey().getCommit(),
                        entry.getKey().getModuleCode(), entry.getValue(), blockHeader);
            }
            if (!rs) {
                result = false;
                chain.getLoggerMap().get(TxConstant.LOG_TX).debug("save tx failed! commitTxs");
                break;
            }
            successed.put(entry.getKey(), entry.getValue());
        }
        if (!result && atomicity) {
            rollbackTxs(chain, successed, blockHeader, false);
            return false;
        }
        return true;
    }

    /**提交账本*/
    private boolean commitLedger(Chain chain, List<String> txList, long blockHeight) {
        try {
            boolean rs = LedgerCall.commitTxsLedger(chain, txList, blockHeight);
            if(!rs){
                chain.getLoggerMap().get(TxConstant.LOG_TX).debug("save block tx failed! commitLedger");
            }
            return rs;
        } catch (NulsException e) {
            chain.getLoggerMap().get(TxConstant.LOG_TX).debug("failed! commitLedger");
            chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
            return false;
        }
    }

    /**从已确认库中删除交易*/
    private boolean removeTxs(Chain chain, List<Transaction> txList, long blockheight, boolean atomicity) {
        boolean rs = true;
        if(!confirmedTxStorageService.removeTxList(chain.getChainId(), txList) && atomicity ){
            saveTxs(chain, txList, blockheight, false);
            rs = false;
            chain.getLoggerMap().get(TxConstant.LOG_TX).debug("failed! removeTxs");
        }
        return rs;
    }

    /**回滚交易业务数据*/
    private boolean rollbackTxs(Chain chain, Map<TxRegister, List<String>> moduleVerifyMap, String blockHeader, boolean atomicity) {
        Map<TxRegister, List<String>> successed = new HashMap<>(TxConstant.INIT_CAPACITY_8);
        boolean result = true;
        for (Map.Entry<TxRegister, List<String>> entry : moduleVerifyMap.entrySet()) {
            boolean rs;
            if (entry.getKey().getModuleCode().equals(txConfig.getModuleCode())) {
                try {
                    rs = txService.crossTransactionRollback(chain, entry.getValue(), blockHeader);
                } catch (NulsException e) {
                    chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
                    rs = false;
                }
            } else {
                rs = TransactionCall.txProcess(chain, entry.getKey().getRollback(),
                        entry.getKey().getModuleCode(), entry.getValue(), blockHeader);
            }
            if (!rs) {
                result = false;
                chain.getLoggerMap().get(TxConstant.LOG_TX).debug("failed! rollbackcommitTxs ");
                break;
            }
            successed.put(entry.getKey(), entry.getValue());
        }
        if (!result && atomicity) {
            commitTxs(chain, successed, blockHeader, false);
            return false;
        }
        return true;
    }

    /**回滚已确认交易账本*/
    private boolean rollbackLedger(Chain chain, List<String> txList, Long blockHeight) {
        try {
            boolean rs =  LedgerCall.rollbackTxsLedger(chain, txList, blockHeight);
            if(!rs){
                chain.getLoggerMap().get(TxConstant.LOG_TX).debug("rollback block tx failed! rollbackLedger");
            }
            return rs;
        } catch (NulsException e) {
            chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
            return false;
        }
    }


    @Override
    public boolean rollbackTxList(Chain chain, List<NulsDigestData> txHashList, String blockHeaderStr) throws NulsException {
        chain.getLoggerMap().get(TxConstant.LOG_TX).debug("start rollbackTxList..............");
        if (null == chain || txHashList == null || txHashList.size() == 0) {
            throw new NulsException(TxErrorCode.PARAMETER_ERROR);
        }
        int chainId = chain.getChainId();
        List<byte[]> txHashs = new ArrayList<>();
        List<Transaction> txList = new ArrayList<>();
        List<String> txStrList = new ArrayList<>();
        //组装统一验证参数数据,key为各模块统一验证器cmd
        Map<TxRegister, List<String>> moduleVerifyMap = new HashMap<>(TxConstant.INIT_CAPACITY_8);
        try {
            for (int i = 0; i < txHashList.size(); i++) {
                NulsDigestData hash = txHashList.get(i);
                txHashs.add(hash.serialize());
                TransactionConfirmedPO txPO = confirmedTxStorageService.getTx(chainId, hash);
                Transaction tx = txPO.getTx();
                txList.add(tx);
                String txStr = RPCUtil.encode(tx.serialize());
                txStrList.add(txStr);
                TxUtil.moduleGroups(chain, moduleVerifyMap, tx);
            }
        } catch (Exception e) {
            chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
            return false;
        }

        BlockHeader blockHeader = TxUtil.getInstanceRpcStr(blockHeaderStr, BlockHeader.class);
        chain.getLoggerMap().get(TxConstant.LOG_TX).debug("rollbackTxList block height:{}", blockHeader.getHeight());
        if (!rollbackLedger(chain, txStrList, blockHeader.getHeight())) {
            return false;
        }

        if (!rollbackTxs(chain, moduleVerifyMap, blockHeaderStr, true)) {
            commitLedger(chain, txStrList, blockHeader.getHeight());
            return false;
        }
        if (!removeTxs(chain, txList, blockHeader.getHeight(), true)) {
            commitTxs(chain, moduleVerifyMap, blockHeaderStr, false);
            saveTxs(chain, txList, blockHeader.getHeight(), false);
            return false;
        }

        //倒序放入未确认库, 和待打包队列
        for (int i = txList.size() - 1; i >= 0; i--) {
            Transaction tx = txList.get(i);
            if(!TxManager.isSystemTx(chain, tx)) {
                unconfirmedTxStorageService.putTx(chain.getChainId(), tx);
                savePackable(chain, tx);
            }
        }
        return true;
    }

    /**
     * 重新放回待打包队列的最前端
     *
     * @param chain chain
     * @param tx    Transaction
     * @return boolean
     */
    private boolean savePackable(Chain chain, Transaction tx) {
        //不是系统交易 并且节点是打包节点则重新放回待打包队列的最前端
        if (chain.getPackaging().get()) {
            packablePool.addInFirst(chain, tx);
        }
        return true;
    }


    @Override
    public void processEffectCrossTx(Chain chain, long blockHeight) throws NulsException {
        int chainId = chain.getChainId();
        List<NulsDigestData> hashList = confirmedTxStorageService.getCrossTxEffectList(chainId, blockHeight);
        for (NulsDigestData hash : hashList) {
            TransactionConfirmedPO txPO = confirmedTxStorageService.getTx(chainId, hash);
            Transaction tx = txPO.getTx();
            if (null == tx) {
                chain.getLoggerMap().get(TxConstant.LOG_TX).error(TxErrorCode.TX_NOT_EXIST.getMsg() + ": " + hash.toString());
                continue;
            }
            if (tx.getType() != TxConstant.TX_TYPE_CROSS_CHAIN_TRANSFER) {
                chain.getLoggerMap().get(TxConstant.LOG_TX).error(TxErrorCode.TX_TYPE_ERROR.getMsg() + ": " + hash.toString());
                continue;
            }
            //跨链转账交易接收者链id
            int toChainId = TxUtil.getCrossTxTosOriginChainId(tx);

            /*
                如果当前链是主网
                    1.需要对接收者链进行账目金额增加
                    2a.如果是交易收款方,则需要向发起链发送回执? todo
                    2b.如果不是交易收款方广播给收款方链
                如果当前链是交易发起链
                    1.广播给主网
             */
            if (chainId == txConfig.getMainChainId()) {
                if (toChainId == chainId) {
                    //todo 已到达目标链发送回执
                } else {
                    //广播给 toChainId 链的节点
                    NetworkCall.broadcastTxHash(toChainId, tx.getHash());
                }
            } else {
                //广播给 主网 链的节点
                NetworkCall.broadcastTxHash(txConfig.getMainChainId(), tx.getHash());
            }
        }
    }

    @Override
    public List<String> getTxList(Chain chain, List<String> hashList) {
        List<String> txList = new ArrayList<>();
        if (hashList == null || hashList.size() == 0) {
            return txList;
        }
        int chainId = chain.getChainId();
        for(String hashHex : hashList){
            TransactionConfirmedPO txCfmPO = confirmedTxStorageService.getTx(chainId, hashHex);
            try {
                txList.add(RPCUtil.encode(txCfmPO.getTx().serialize()));
            } catch (Exception e) {
                chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
                return new ArrayList<>();
            }
        }
        return txList;
    }

    @Override
    public List<String> getTxListExtend(Chain chain, List<String> hashList, boolean allHits) {
        List<String> txList = new ArrayList<>();
        if (hashList == null || hashList.size() == 0) {
            return txList;
        }
        int chainId = chain.getChainId();
        for(String hashHex : hashList){
            Transaction tx = unconfirmedTxStorageService.getTx(chain.getChainId(), hashHex);
            if(null == tx) {
                TransactionConfirmedPO txCfmPO = confirmedTxStorageService.getTx(chainId, hashHex);
                if(null == txCfmPO){
                    if(allHits) {
                        //allHits为true时一旦有一个没有获取到, 直接返回空list
                        return new ArrayList<>();
                    }
                    continue;
                }
                tx = txCfmPO.getTx();
            }
            try {
                txList.add(RPCUtil.encode(tx.serialize()));
            } catch (Exception e) {
                chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
                if(allHits) {
                    //allHits为true时直接返回空list
                    return new ArrayList<>();
                }
                continue;
            }
        }
        return txList;
    }
}
