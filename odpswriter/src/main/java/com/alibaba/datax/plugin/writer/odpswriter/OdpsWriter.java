package com.alibaba.datax.plugin.writer.odpswriter;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.plugin.SlavePluginCollector;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.common.util.ListUtil;
import com.alibaba.datax.common.util.StrUtil;
import com.alibaba.datax.plugin.writer.odpswriter.util.IdAndKeyUtil;
import com.alibaba.datax.plugin.writer.odpswriter.util.OdpsUtil;
import com.alibaba.odps.tunnel.DataTunnel;
import com.alibaba.odps.tunnel.RecordSchema;
import com.alibaba.odps.tunnel.Upload;
import com.aliyun.openservices.odps.Project;
import com.aliyun.openservices.odps.tables.Table;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class OdpsWriter extends Writer {

    public static class Master extends Writer.Master {
        private static final Logger LOG = LoggerFactory
                .getLogger(OdpsWriter.Master.class);

        private static final boolean IS_DEBUG = LOG.isDebugEnabled();

        private Configuration originalConfig;
        private String project;
        private String table;
        private String partition;
        private String accountType;
        private boolean truncate;
        private String uploadId;
        private Upload masterUpload;

        @Override
        public void init() {
            this.originalConfig = super.getPluginJobConf();

            OdpsUtil.checkNecessaryConfig(this.originalConfig);
            OdpsUtil.dealMaxRetryTime(this.originalConfig);

            this.project = this.originalConfig.getString(Key.PROJECT);
            this.table = this.originalConfig.getString(Key.TABLE);

            this.partition = OdpsUtil.formatPartition(this.originalConfig
                    .getString(Key.PARTITION, ""));
            this.originalConfig.set(Key.PARTITION, this.partition);

            this.accountType = this.originalConfig.getString(Key.ACCOUNT_TYPE,
                    Constant.DEFAULT_ACCOUNT_TYPE);
            if (!Constant.DEFAULT_ACCOUNT_TYPE.equalsIgnoreCase(this.accountType) &&
                    !Constant.TAOBAO_ACCOUNT_TYPE.equalsIgnoreCase(this.accountType)) {
                String businessMessage = String.format("unsupported account type=[%s].",
                        this.accountType);
                String message = StrUtil.buildOriginalCauseMessage(
                        businessMessage, null);

                LOG.error(message);
                throw new DataXException(OdpsWriterErrorCode.UNSUPPORTED_ACCOUNT_TYPE,
                        businessMessage);
            }
            this.originalConfig.set(Key.ACCOUNT_TYPE, Constant.DEFAULT_ACCOUNT_TYPE);

            this.truncate = this.originalConfig.getBool(Key.TRUNCATE);

            if (IS_DEBUG) {
                LOG.debug("After init, job config now is: [\n{}\n] .",
                        this.originalConfig.toJSON());
            }
        }

        @Override
        public void prepare() {
            String accessId = null;
            String accessKey = null;

            if (Constant.DEFAULT_ACCOUNT_TYPE
                    .equalsIgnoreCase(this.accountType)) {
                this.originalConfig = IdAndKeyUtil.parseAccessIdAndKey(this.originalConfig);
                accessId = this.originalConfig.getString(Key.ACCESS_ID);
                accessKey = this.originalConfig.getString(Key.ACCESS_KEY);
                if (IS_DEBUG) {
                    LOG.debug("accessId:[{}], accessKey:[{}] .", accessId,
                            accessKey);
                }

                LOG.info("accessId:[{}] .", accessId);
            }


            // init odps config
            Project odpsProject = OdpsUtil.initOdpsProject(this.originalConfig);

            String table = this.originalConfig.getString(Key.TABLE);
            Table tab = new Table(odpsProject, table);

            //检查表等配置是否正确
            try {
                tab.load();
            } catch (Exception e) {
                String businessMessage = String.format("Can not load table. detail: table=[%s]. detail:[%s].",
                        tab.getName(), e.getMessage());
                String message = StrUtil.buildOriginalCauseMessage(businessMessage, null);
                LOG.error(message);

                throw new DataXException(OdpsWriterErrorCode.CONFIG_INNER_ERROR, e);
            }

            OdpsUtil.dealTruncate(tab, this.partition, this.truncate);
        }

        /**
         * 此处主要是对 uploadId进行设置，以及对 blockId 的开始值进行设置。
         * <p/>
         * 对 blockId 需要同时设置开始值以及下一个 blockId 的步长值(INTERVAL_STEP)。
         */
        @Override
        public List<Configuration> split(int mandatoryNumber) {
            List<Configuration> configurations = new ArrayList<Configuration>();

            // init dataTunnel config
            DataTunnel dataTunnel = OdpsUtil.initDataTunnel(this.originalConfig);

            this.masterUpload = OdpsUtil.createMasterTunnelUpload(
                    dataTunnel, this.project, this.table, this.partition);
            this.uploadId = this.masterUpload.getUploadId();
            LOG.info("uploadId:[{}].", this.uploadId);

            RecordSchema schema = this.masterUpload.getSchema();
            List<String> allColumns = OdpsUtil.getAllColumns(schema);
            LOG.info("allColumnList: {} .", StringUtils.join(allColumns, ','));

            dealColumn(this.originalConfig, allColumns);

            for (int i = 0; i < mandatoryNumber; i++) {
                Configuration tempConfig = this.originalConfig.clone();

                tempConfig.set(Constant.UPLOAD_ID, this.uploadId);
                tempConfig.set(Constant.BLOCK_ID, String.valueOf(i));
                tempConfig.set(Constant.INTERVAL_STEP, mandatoryNumber);

                configurations.add(tempConfig);
            }

            if (IS_DEBUG) {
                LOG.debug("After master split, the job config now is:[\n{}\n].", this.originalConfig);
            }

            return configurations;
        }

        private void dealColumn(Configuration originalConfig, List<String> allColumns) {
            List<String> userConfiguredColumns = originalConfig.getList(Key.COLUMN, String.class);
            if (1 == userConfiguredColumns.size() && "*".equals(userConfiguredColumns.get(0))) {
                userConfiguredColumns = allColumns;
                originalConfig.set(Key.COLUMN, allColumns);
            } else {
                //检查列是否重复，大小写不敏感（所有写入，都是不允许写入段的列重复的）
                ListUtil.makeSureNoValueDuplicate(userConfiguredColumns, false);

                //检查列是否存在，大小写不敏感
                ListUtil.makeSureBInA(allColumns, userConfiguredColumns, false);
            }

            List<Integer> columnPositions = OdpsUtil.parsePosition(allColumns, userConfiguredColumns);
            originalConfig.set(Constant.COLUMN_POSITION, columnPositions);
        }

        @Override
        public void post() {
            List<Long> blocks = new ArrayList<Long>();
            List<String> salveWroteBlockIds = super.getMasterPluginCollector()
                    .getMessage(Constant.SLAVE_WROTE_BLOCK_MESSAGE);

            for (String context : salveWroteBlockIds) {
                String[] blockIdStrs = context.split(",");
                for (int i = 0, len = blockIdStrs.length; i < len; i++) {
                    blocks.add(Long.parseLong(blockIdStrs[i]));
                }
            }

            LOG.info("Master begin to commit blocks:[\n{}\n].", StringUtils.join(blocks, ","));
            OdpsUtil.masterCompleteBlocks(this.masterUpload, blocks.toArray(new Long[0]));
        }

        @Override
        public void destroy() {
        }
    }


    public static class Slave extends Writer.Slave {
        private static final Logger LOG = LoggerFactory
                .getLogger(OdpsWriter.Slave.class);

        private static final boolean IS_DEBUG = LOG.isDebugEnabled();

        private Configuration sliceConfig;
        private DataTunnel dataTunnel;
        private String project;
        private String table;
        private String partition;
        private Upload slaveUpload;

        private String uploadId = null;

        @Override
        public void init() {
            this.sliceConfig = super.getPluginJobConf();

            this.project = this.sliceConfig.getString(Key.PROJECT);
            this.table = this.sliceConfig.getString(Key.TABLE);
            this.partition = OdpsUtil.formatPartition(this.sliceConfig
                    .getString(Key.PARTITION, ""));
            this.sliceConfig.set(Key.PARTITION, this.partition);

            this.dataTunnel = OdpsUtil.initDataTunnel(this.sliceConfig);

            uploadId = this.sliceConfig.getString(Constant.UPLOAD_ID);
            this.slaveUpload = OdpsUtil.getSlaveTunnelUpload(this.dataTunnel, this.project,
                    this.table, this.partition, uploadId);

            if (IS_DEBUG) {
                LOG.debug("After init in slave, sliceConfig now is:[\n{}\n].", this.sliceConfig);
            }

        }

        @Override
        public void prepare() {
        }

        @Override
        public void startWrite(RecordReceiver recordReceiver) {
            // blockId 的开始值，master在 split 的时候已经准备好
            long blockId = this.sliceConfig.getLong(Constant.BLOCK_ID);
            int intervalStep = this.sliceConfig.getInt(Constant.INTERVAL_STEP);
            List<Long> blocks = new ArrayList<Long>();
            List<Integer> columnPositions = this.sliceConfig.getList(Constant.COLUMN_POSITION,
                    Integer.class);

            try {
                SlavePluginCollector slavePluginCollector = super.getSlavePluginCollector();

                OdpsWriterProxy proxy = new OdpsWriterProxy(this.slaveUpload, blockId, intervalStep,
                        columnPositions);

                com.alibaba.datax.common.element.Record dataxRecord = null;
                while ((dataxRecord = recordReceiver.getFromReader()) != null) {
                    try {
                        proxy.writeOneRecord(dataxRecord, blocks);
                    } catch (DataXException e1) {
                        throw e1;
                    } catch (Exception e2) {
                        slavePluginCollector.collectDirtyRecord(dataxRecord,
                                "Write the record failed, because: " + e2.getMessage());
                    }
                }

                proxy.writeRemainingRecord(blocks);

                slavePluginCollector.collectMessage(Constant.SLAVE_WROTE_BLOCK_MESSAGE,
                        StringUtils.join(blocks, ","));
            } catch (Exception e) {
                String businessMessage = "Write record failed. detail:" + e.getMessage();
                String message = StrUtil.buildOriginalCauseMessage(
                        businessMessage, null);
                LOG.error(message);

                throw new DataXException(OdpsWriterErrorCode.WRITER_RECORD_FAIL, e);
            }
        }

        @Override
        public void post() {
        }

        @Override
        public void destroy() {
        }

    }
}