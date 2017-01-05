/**
 * Copyright 2016 vip.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 * </p>
 */

package com.vip.saturn.job.basic;

import java.util.Date;

import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.spi.OperableTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vip.saturn.job.exception.JobException;
import com.vip.saturn.job.executor.LimitMaxJobsService;
import com.vip.saturn.job.executor.SaturnExecutorService;
import com.vip.saturn.job.internal.analyse.AnalyseService;
import com.vip.saturn.job.internal.config.ConfigurationService;
import com.vip.saturn.job.internal.config.JobConfiguration;
import com.vip.saturn.job.internal.control.ControlService;
import com.vip.saturn.job.internal.election.LeaderElectionService;
import com.vip.saturn.job.internal.execution.ExecutionContextService;
import com.vip.saturn.job.internal.execution.ExecutionService;
import com.vip.saturn.job.internal.execution.NextFireTimePausePeriodEffected;
import com.vip.saturn.job.internal.failover.FailoverService;
import com.vip.saturn.job.internal.listener.ListenerManager;
import com.vip.saturn.job.internal.offset.OffsetService;
import com.vip.saturn.job.internal.server.ServerService;
import com.vip.saturn.job.internal.sharding.ShardingService;
import com.vip.saturn.job.internal.statistics.StatisticsService;
import com.vip.saturn.job.internal.storage.JobNodePath;
import com.vip.saturn.job.internal.storage.JobNodeStorage;
import com.vip.saturn.job.reg.base.CoordinatorRegistryCenter;
import com.vip.saturn.job.trigger.SaturnScheduler;

/**
 * 作业调度器.
 * @author dylan.xue
 */
public class JobScheduler {
	static Logger log = LoggerFactory.getLogger(JobScheduler.class);

	private String jobName;

	private String executorName;

	/** since all the conf-node values will be gotten from zk-cache. use this to compare with the new values. */

	private JobConfiguration previousConf = new JobConfiguration(null, null);

	private final JobConfiguration currentConf;

	private final CoordinatorRegistryCenter coordinatorRegistryCenter;

	private final ListenerManager listenerManager;

	private final ConfigurationService configService;

	private final LeaderElectionService leaderElectionService;

	private final ServerService serverService;
	
	private final ControlService controlService;

	private final ShardingService shardingService;

	private final ExecutionContextService executionContextService;

	private final ExecutionService executionService;

	private final FailoverService failoverService;

	private final StatisticsService statisticsService;

	private final OffsetService offsetService;

	private final AnalyseService analyseService;

	private final LimitMaxJobsService limitMaxJobsService;

	private final JobNodeStorage jobNodeStorage;

	private AbstractElasticJob job;

	private SaturnExecutorService saturnExecutorService;

	public JobScheduler(final CoordinatorRegistryCenter coordinatorRegistryCenter,
			final JobConfiguration jobConfiguration) {
		this.jobName = jobConfiguration.getJobName();
		this.executorName = coordinatorRegistryCenter.getExecutorName();
		this.currentConf = jobConfiguration;
		this.coordinatorRegistryCenter = coordinatorRegistryCenter;
		this.jobNodeStorage = new JobNodeStorage(coordinatorRegistryCenter, jobConfiguration);
		JobRegistry.addJobScheduler(executorName, jobName, this);

		configService = new ConfigurationService(this);
		leaderElectionService = new LeaderElectionService(this);
		serverService = new ServerService(this);
		shardingService = new ShardingService(this);
		executionContextService = new ExecutionContextService(this);
		executionService = new ExecutionService(this);
		failoverService = new FailoverService(this);
		statisticsService = new StatisticsService(this);
		offsetService = new OffsetService(this);
		analyseService = new AnalyseService(this);
		limitMaxJobsService = new LimitMaxJobsService(this);
		listenerManager = new ListenerManager(this);
		controlService = new ControlService(this);

		// see EnabledPathListener.java, only these values are supposed to be watched.
		previousConf.setCron(jobConfiguration.getCron());
		previousConf.setPausePeriodDate(jobConfiguration.getPausePeriodDate());
		previousConf.setPausePeriodTime(jobConfiguration.getPausePeriodTime());
		previousConf.setProcessCountIntervalSeconds(jobConfiguration.getProcessCountIntervalSeconds());
	}

	/**
	 * 初始化作业.
	 */
	public void init() {
		try {
			String currentConfJobName = currentConf.getJobName();
			log.info("[{}] msg=Elastic job: job controller init, job name is: {}.", jobName, currentConfJobName);
			coordinatorRegistryCenter.addCacheData(JobNodePath.getJobNameFullPath(currentConfJobName));

			startAll();
			createJob();
			serverService.persistServerOnline();
		} catch (Throwable t) {
			log.error(String.format(SaturnConstant.ERROR_LOG_FORMAT, jobName, t.getMessage()), t);
			shutdown(false);
		}
	}

	private void startAll() {
		configService.start();
		leaderElectionService.start();
		serverService.start();
		shardingService.start();
		executionContextService.start();
		executionService.start();
		failoverService.start();
		statisticsService.start();
		offsetService.start();
		limitMaxJobsService.start();
		analyseService.start();

		limitMaxJobsService.check(currentConf.getJobName());
		listenerManager.start();
		leaderElectionService.leaderElection();

		serverService.clearRunOneTimePath();
		serverService.clearStopOneTimePath();
		serverService.resetCount();
		statisticsService.startProcessCountJob();
	}

	private void createJob() throws SchedulerException {
		Class<?> jobClass = currentConf.getSaturnJobClass();
		try {
			job = (AbstractElasticJob) jobClass.newInstance();
		} catch (Exception e) {
			log.error(String.format(SaturnConstant.ERROR_LOG_FORMAT, jobName, "createJobException:"), e);
			throw new RuntimeException("can not create job with job type " + currentConf.getJobType());
		}
		job.setJobScheduler(this);
		job.setConfigService(configService);
		job.setShardingService(shardingService);
		job.setExecutionContextService(executionContextService);
		job.setExecutionService(executionService);
		job.setFailoverService(failoverService);
		job.setOffsetService(offsetService);
		job.setServerService(serverService);
		job.setExecutorName(executorName);
		job.setControlService(controlService);
		job.setJobName(jobName);
		job.setNamespace(coordinatorRegistryCenter.getNamespace());
		job.setSaturnExecutorService(saturnExecutorService);
		job.init();
	}

	/**
	 * 获取下次作业触发时间.可能被暂停时间段所影响。
	 * 
	 * @return 下次作业触发时间
	 */
	public NextFireTimePausePeriodEffected getNextFireTimePausePeriodEffected() {
		NextFireTimePausePeriodEffected result = new NextFireTimePausePeriodEffected();
		SaturnScheduler saturnScheduler =  job.getScheduler();
		if(saturnScheduler == null){
			return result;
		}
		Trigger trigger = saturnScheduler.getTrigger();

		if (trigger == null) {
			return result;
		}
		((OperableTrigger) trigger).updateAfterMisfire(null);
		Date nextFireTime = trigger.getNextFireTime();
		while (nextFireTime != null && configService.isInPausePeriod(nextFireTime)) {
			nextFireTime = trigger.getFireTimeAfter(nextFireTime);
		}
		if (null == nextFireTime) {
			return result;
		}
		if (null == result.getNextFireTime() || nextFireTime.getTime() < result.getNextFireTime().getTime()) {
			result.setNextFireTime(nextFireTime);
		}
		return result;
	}

	/**
	 * 停止作业.
	 * @param stopJob 是否强制停止作业
	 */
	public void stopJob(boolean stopJob) {
		if (stopJob) {
			job.abort();
		} else {
			job.stop();
		}
	}

	/**
	 * 恢复因服务器崩溃而停止的作业.
	 * 
	 * <p>
	 * 不会恢复手工设置停止运行的作业.
	 * </p>
	 */
	/*
	 * public void resumeCrashedJob() { serverService.persistServerOnline();
	 * executionService.clearRunningInfo(shardingService.getLocalHostShardingItems()); job.resume(); }
	 */

	/**
	 * 立刻启动作业.
	 */
	public void triggerJob() {
		if (job.getScheduler().isShutdown()) {
			return;
		}
		job.getScheduler().triggerJob();
	}

	/**
	 * 关闭process count thread
	 */
	public void shutdownCountThread(){
		statisticsService.shutdown();
	}
	
	/**
	 * 关闭调度器.
	 */
	public void shutdown(boolean removejob) {
		try {
			if (job != null) {
				job.shutdown();
				Thread.sleep(500);
			}
		} catch (final Exception e) {
			log.error(String.format(SaturnConstant.ERROR_LOG_FORMAT, jobName, e.getMessage()), e);
		}

		listenerManager.shutdown();
		shardingService.shutdown();
		configService.shutdown();
		leaderElectionService.shutdown();
		serverService.shutdown();
		executionContextService.shutdown();
		executionService.shutdown();
		failoverService.shutdown();
		statisticsService.shutdown();
		offsetService.shutdown();
		analyseService.shutdown();
		limitMaxJobsService.shutdown();

		coordinatorRegistryCenter.closeTreeCache(JobNodePath.getJobNameFullPath(jobName));
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
		}
		if (removejob) {
			jobNodeStorage.deleteJobNode();
		}

		JobRegistry.clearJob(executorName, jobName);
	}

	/**
	 * 重新调度作业.
	 * 
	 * @param cronExpression crom表达式
	 */
	public void rescheduleJob(final String cronExpression) {
		try {
			if (job.getScheduler().isShutdown()) {
				return;
			}
			job.getTrigger().retrigger(job.getScheduler(), job);
		} catch (final SchedulerException ex) {
			throw new JobException(ex);
		}
	}

	/**
	 * 重启统计处理数据数量的任务
	 */
	public void rescheduleProcessCountJob() {
		statisticsService.startProcessCountJob();
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getExecutorName() {
		return executorName;
	}

	public void setExecutorName(String executorName) {
		this.executorName = executorName;
	}

	public JobConfiguration getPreviousConf() {
		return previousConf;
	}

	public void setPreviousConf(JobConfiguration previousConf) {
		this.previousConf = previousConf;
	}

	public AbstractElasticJob getJob() {
		return job;
	}

	public void setJob(AbstractElasticJob job) {
		this.job = job;
	}

	public SaturnExecutorService getSaturnExecutorService() {
		return saturnExecutorService;
	}

	public void setSaturnExecutorService(SaturnExecutorService saturnExecutorService) {
		this.saturnExecutorService = saturnExecutorService;
	}

	public JobConfiguration getCurrentConf() {
		return currentConf;
	}

	public CoordinatorRegistryCenter getCoordinatorRegistryCenter() {
		return coordinatorRegistryCenter;
	}

	public ListenerManager getListenerManager() {
		return listenerManager;
	}

	public ConfigurationService getConfigService() {
		return configService;
	}
	
	public ControlService getControlService() {
		return controlService;
	}

	public LeaderElectionService getLeaderElectionService() {
		return leaderElectionService;
	}

	public ServerService getServerService() {
		return serverService;
	}

	public ShardingService getShardingService() {
		return shardingService;
	}

	public ExecutionContextService getExecutionContextService() {
		return executionContextService;
	}

	public ExecutionService getExecutionService() {
		return executionService;
	}

	public FailoverService getFailoverService() {
		return failoverService;
	}

	public StatisticsService getStatisticsService() {
		return statisticsService;
	}

	public OffsetService getOffsetService() {
		return offsetService;
	}

	public AnalyseService getAnalyseService() {
		return analyseService;
	}

	public LimitMaxJobsService getLimitMaxJobsService() {
		return limitMaxJobsService;
	}

	public JobNodeStorage getJobNodeStorage() {
		return jobNodeStorage;
	}
}
