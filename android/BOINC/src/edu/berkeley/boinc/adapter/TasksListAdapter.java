/*******************************************************************************
 * This file is part of BOINC.
 * http://boinc.berkeley.edu
 * Copyright (C) 2012 University of California
 * 
 * BOINC is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 * 
 * BOINC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with BOINC.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package edu.berkeley.boinc.adapter;

import edu.berkeley.boinc.utils.*;

import java.sql.Date;
import java.util.ArrayList;

import edu.berkeley.boinc.R;
import edu.berkeley.boinc.TasksActivity.TaskData;
import edu.berkeley.boinc.client.Monitor;
import edu.berkeley.boinc.rpc.RpcClient;
import edu.berkeley.boinc.utils.BOINCDefs;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

public class TasksListAdapter extends ArrayAdapter<TaskData>{
	private ArrayList<TaskData> entries;
	private Activity activity;

	public TasksListAdapter(Activity a, int textViewResourceId, ArrayList<TaskData> entries) {
		super(a, textViewResourceId, entries);
		this.entries = entries;
		this.activity = a;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {

		TaskData listItem = entries.get(position);

		View v = convertView;
		// setup new view, if:
		// - view is null, has not been here before
		// - view has different id
		Boolean setup = false;
		if(v == null) setup = true;
		else {
			String viewId = (String)v.getTag();
			if(!listItem.id.equals(viewId)) setup = true;
		}
		
		if(setup){
			LayoutInflater vi = (LayoutInflater)activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			v = vi.inflate(R.layout.tasks_layout_listitem, null);
			v.setTag(listItem.id);
		}
		
		ProgressBar epb = (ProgressBar) v.findViewById(R.id.progressBar);
		ProgressBar cpb = (ProgressBar) v.findViewById(R.id.collapsedProgressBar);
		TextView header = (TextView) v.findViewById(R.id.taskHeader);
		TextView status = (TextView) v.findViewById(R.id.taskStatus);
		TextView time = (TextView) v.findViewById(R.id.taskTime);
		TextView cpbPercentageText = (TextView) v.findViewById(R.id.taskProgressCollapsedActive);
		TextView statusPercentage = (TextView) v.findViewById(R.id.taskStatusPercentage);
		
		// set up view elements that are independent of "active" and "expanded" state
		ImageView ivIcon = (ImageView)v.findViewById(R.id.projectIcon);
		String finalIconId = (String)ivIcon.getTag();
	    if(finalIconId == null || !finalIconId.equals(listItem.id)) {
			Bitmap icon = getIcon(position);
			// if available set icon, if not boinc logo
			if(icon == null) { 
				ivIcon.setImageDrawable(getContext().getResources().getDrawable(R.drawable.boinc));
			} else {
				ivIcon.setImageBitmap(icon);
				ivIcon.setTag(listItem.id);
			}
		}
		
		String headerT = listItem.result.app.getName();
		header.setText(headerT);
		
		// set project name
		String tempProjectName = listItem.result.project_url;
		if(listItem.result.project != null) {
			tempProjectName = listItem.result.project.getName();
			if(listItem.result.project_suspended_via_gui) {
				tempProjectName = tempProjectName + " " + getContext().getString(R.string.tasks_header_project_paused);
			}
		}
		((TextView) v.findViewById(R.id.projectName)).setText(tempProjectName);
		// --- end of independent view elements
		
		RelativeLayout expansionWrapper = (RelativeLayout) v.findViewById(R.id.expansion);
		LinearLayout statusTextWrapper = (LinearLayout) v.findViewById(R.id.statusTextWrapper);
		RelativeLayout statusCollapsedActiveWrapper = (RelativeLayout) v.findViewById(R.id.statusCollapsedActiveWrapper);
		if(!listItem.expanded) {
			// view is collapsed
			((ImageView)v.findViewById(R.id.expandCollapse)).setImageResource(R.drawable.collapse);
			expansionWrapper.setVisibility(View.GONE);

			// result and process state are overlapping, e.g. PROCESS_EXECUTING and RESULT_FILES_DOWNLOADING
			// therefore check also whether task is active
			if(listItem.isTaskActive() && listItem.determineState() == BOINCDefs.PROCESS_EXECUTING) {
				// task is active
				statusTextWrapper.setVisibility(View.GONE);
				statusCollapsedActiveWrapper.setVisibility(View.VISIBLE);
				cpb.setIndeterminate(false);
				cpb.setProgressDrawable(this.activity.getResources().getDrawable(R.drawable.progressbar));
				determineProgress(listItem, cpbPercentageText, cpb);
			} else {
				// task is not active
				statusTextWrapper.setVisibility(View.VISIBLE);
				statusCollapsedActiveWrapper.setVisibility(View.GONE);
				
				String statusT = determineStatusText(listItem);
				status.setText(statusT);
				
				determineProgress(listItem, statusPercentage, epb);
			}
		} else {
			// view is expanded
			((ImageView)v.findViewById(R.id.expandCollapse)).setImageResource(R.drawable.expand);
			expansionWrapper.setVisibility(View.VISIBLE);
			statusTextWrapper.setVisibility(View.VISIBLE);
			statusCollapsedActiveWrapper.setVisibility(View.GONE);
			
			// status text
			String statusT = determineStatusText(listItem);
			status.setText(statusT);
			
			// progress bar
			epb.setIndeterminate(false);
			epb.setProgressDrawable(this.activity.getResources().getDrawable(R.drawable.progressbar));
			determineProgress(listItem, statusPercentage, epb);
			
			// elapsed time
			int elapsedTime;
			// show time depending whether task is active or not
			if(listItem.result.active_task) elapsedTime = (int)listItem.result.elapsed_time; //is 0 when task finished
			else elapsedTime = (int) listItem.result.final_elapsed_time;
			time.setText(String.format("%02d:%02d:%02d", elapsedTime/3600, (elapsedTime/60)%60, elapsedTime%60));
			
			// set deadline
			String deadline = (String) DateFormat.format("E d MMM yyyy hh:mm:ss aa", new Date(listItem.result.report_deadline*1000));
			((TextView) v.findViewById(R.id.deadline)).setText(deadline);
			// set application friendly name
			if(listItem.result.app != null) {
				((TextView) v.findViewById(R.id.taskName)).setText(listItem.result.name);
			}
			
			// buttons
			ImageView suspendResume = (ImageView) v.findViewById(R.id.suspendResumeTask);
			ImageView abortButton = (ImageView) v.findViewById(R.id.abortTask);
			if(listItem.determineState() == BOINCDefs.PROCESS_ABORTED) { //dont show buttons for aborted task
				((RelativeLayout)v.findViewById(R.id.taskButtons)).setVisibility(View.INVISIBLE);
			} else {
				if (listItem.nextState == -1) { // not waiting for new state
					suspendResume.setOnClickListener(listItem.iconClickListener);

					abortButton.setOnClickListener(listItem.iconClickListener);
					abortButton.setTag(RpcClient.RESULT_ABORT); // tag on button specified operation triggered in iconClickListener
					abortButton.setVisibility(View.VISIBLE);
					
					((ProgressBar)v.findViewById(R.id.request_progressBar)).setVisibility(View.GONE);

					// checking what suspendResume button should be shown
					if(listItem.result.suspended_via_gui) { // show play
						suspendResume.setVisibility(View.VISIBLE);
						suspendResume.setImageResource(R.drawable.resumetask);
						suspendResume.setTag(RpcClient.RESULT_RESUME); // tag on button specified operation triggered in iconClickListener

					} else if (listItem.determineState() == BOINCDefs.PROCESS_EXECUTING){ // show pause
						suspendResume.setVisibility(View.VISIBLE);
						suspendResume.setImageResource(R.drawable.pausetask);
						suspendResume.setTag(RpcClient.RESULT_SUSPEND); // tag on button specified operation triggered in iconClickListener

					} else { // show nothing
						suspendResume.setVisibility(View.GONE);
					}
				} else {
					suspendResume.setVisibility(View.INVISIBLE);
					abortButton.setVisibility(View.INVISIBLE);
					((ProgressBar)v.findViewById(R.id.request_progressBar)).setVisibility(View.VISIBLE);
				}
			}
		}

		return v;
	}
	
	private void determineProgress(TaskData data, TextView progress, ProgressBar pb) {
		Float fraction = Float.valueOf((float) 1.0); // default is 100 (e.g. abort show full red progress bar)
		if(!data.result.active_task && data.result.ready_to_report) { //fraction not available
			progress.setVisibility(View.GONE);
		} else { // fraction available
			fraction =  data.result.fraction_done;
			progress.setVisibility(View.VISIBLE);
			progress.setText(Math.round(fraction * 100) + "%");
		}
		pb.setProgress(Math.round(fraction * pb.getMax()));
	}

	
	private Bitmap getIcon(int position) {
		return Monitor.getClientStatus().getProjectIcon(entries.get(position).result.project_url);
	}

	private String determineStatusText(TaskData tmp) {
		
		//read status
		Integer status = tmp.determineState();
		//if(Logging.DEBUG) Log.d(Logging.TAG,"determineStatusText for status: " + status);
		
		// custom state
		if(status == BOINCDefs.RESULT_SUSPENDED_VIA_GUI) return activity.getString(R.string.tasks_custom_suspended_via_gui);
		if(status == BOINCDefs.RESULT_PROJECT_SUSPENDED) return activity.getString(R.string.tasks_custom_project_suspended_via_gui);
		if(status == BOINCDefs.RESULT_READY_TO_REPORT) return activity.getString(R.string.tasks_custom_ready_to_report);
		
		//active state
		if(tmp.result.active_task) {
			switch(status) {
			case BOINCDefs.PROCESS_UNINITIALIZED:
				return activity.getString(R.string.tasks_active_uninitialized);
			case BOINCDefs.PROCESS_EXECUTING:
				return activity.getString(R.string.tasks_active_executing);
			case BOINCDefs.PROCESS_ABORT_PENDING:
				return activity.getString(R.string.tasks_active_abort_pending);
			case BOINCDefs.PROCESS_QUIT_PENDING:
				return activity.getString(R.string.tasks_active_quit_pending);
			case BOINCDefs.PROCESS_SUSPENDED:
				return activity.getString(R.string.tasks_active_suspended);
			default:
				if(Logging.WARNING) Log.w(Logging.TAG,"determineStatusText could not map: " + tmp.determineState());
				return "";
			}
		} else { 
			// passive state
			switch(status) {
			case BOINCDefs.RESULT_NEW:
				return activity.getString(R.string.tasks_result_new);
			case BOINCDefs.RESULT_FILES_DOWNLOADING:
				return activity.getString(R.string.tasks_result_files_downloading);
			case BOINCDefs.RESULT_FILES_DOWNLOADED:
				return activity.getString(R.string.tasks_result_files_downloaded);
			case BOINCDefs.RESULT_COMPUTE_ERROR:
				return activity.getString(R.string.tasks_result_compute_error);
			case BOINCDefs.RESULT_FILES_UPLOADING:
				return activity.getString(R.string.tasks_result_files_uploading);
			case BOINCDefs.RESULT_FILES_UPLOADED:
				return activity.getString(R.string.tasks_result_files_uploaded);
			case BOINCDefs.RESULT_ABORTED:
				return activity.getString(R.string.tasks_result_aborted);
			case BOINCDefs.RESULT_UPLOAD_FAILED:
				return activity.getString(R.string.tasks_result_upload_failed);
			default:
				if(Logging.WARNING) Log.w(Logging.TAG,"determineStatusText could not map: " + tmp.determineState());
				return "";
			}
		}
	}
}
