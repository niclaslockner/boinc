<?php

// This file is part of BOINC.
// http://boinc.berkeley.edu
// Copyright (C) 2011 University of California
//
// BOINC is free software; you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License
// as published by the Free Software Foundation,
// either version 3 of the License, or (at your option) any later version.
//
// BOINC is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with BOINC.  If not, see <http://www.gnu.org/licenses/>.

// server-side utility functions for remote job submissions and control

require_once("../inc/submit_db.inc");

// the physical name of a file is jf_(md5).
// Prepend the jf_ to make the source of the file clear
//
function job_file_name($md5) {
    return "jf_$md5";
}

function authenticate_user($r, $app) {
    $auth = (string)$r->authenticator;
    if (!$auth) xml_error(-1, "no authenticator");
    $auth = BoincDb::escape_string($auth);
    $user = BoincUser::lookup("authenticator='$auth'");
    if (!$user) xml_error(-1, "bad authenticator");
    $user_submit = BoincUserSubmit::lookup_userid($user->id);
    if (!$user_submit) xml_error(-1, "no submit access");
    if ($app && !$user_submit->submit_all) {
        $usa = BoincUserSubmitApp::lookup("user_id=$user->id and app_id=$app->id");
        if (!$usa) {
            xml_error(-1, "no submit access");
        }
    }
    return array($user, $user_submit);
}

// given its WUs, compute params of a batch
// NOTE: eventually this should be done by server components
// (transitioner, validator etc.) as jobs complete or time out
//
// TODO: update est_completion_time
//
function get_batch_params($batch, $wus) {
    $fp_total = 0;
    $fp_done = 0;
    $completed = true;
    $batch->nerror_jobs = 0;
    $batch->credit_canonical = 0;
    foreach ($wus as $wu) {
        $fp_total += $wu->rsc_fpops_est;
        if ($wu->canonical_resultid) {
            $fp_done += $wu->rsc_fpops_est;
            $batch->credit_canonical += $wu->canonical_credit;
        } else if ($wu->error_mask) {
            $batch->nerror_jobs++;
        } else {
            $completed = false;
        }
    }
    if ($fp_total) {
        $batch->fraction_done = $fp_done / $fp_total;
    }
    if ($completed && $batch->state == BATCH_STATE_IN_PROGRESS) {
        $batch->state = BATCH_STATE_COMPLETE;
        $batch->completion_time = time();
    }
    $batch->update("fraction_done = $batch->fraction_done, nerror_jobs = $batch->nerror_jobs, state=$batch->state, completion_time = $batch->completion_time, credit_canonical = $batch->credit_canonical");

    $batch->credit_estimate = flops_to_credit($fp_total);
    return $batch;
}

function get_outfile_names($result) {
    $names = array();
    $xml = "<a>".$result->xml_doc_out."</a>";
    $r = simplexml_load_string($xml);
    if (!$r) return $names;
    foreach ($r->file_info as $fi) {
        $names[] = (string)($fi->name);
    }
    return $names;
}

function get_outfile_paths($result) {
    $fanout = parse_config(get_config(), "<uldl_dir_fanout>");
    $upload_dir = parse_config(get_config(), "<upload_dir>");

    $paths = array();
    $xml = "<a>".$result->xml_doc_out."</a>";
    $r = simplexml_load_string($xml);
    if (!$r) return $paths;
    foreach ($r->file_info as $fi) {
        $path = dir_hier_path((string)($fi->name), $upload_dir, $fanout);
        $paths[] = $path;
    }
    return $paths;
}

function abort_workunit($wu) {
    BoincResult::update_aux(
        "server_state=5, outcome=5 where server_state=2 and workunitid=$wu->id"
    );
    $wu->update("error_mask=error_mask|16");
}

function abort_batch($batch) {
    $wus = BoincWorkunit::enum("batch=$batch->id");
    foreach ($wus as $wu) {
        abort_workunit($wu);
    }
    $batch->update("state=".BATCH_STATE_ABORTED);
    return 0;
}

function retire_batch($batch) {
    $wus = BoincWorkunit::enum("batch=$batch->id");
    $now = time();
    foreach ($wus as $wu) {
        $wu->update("assimilate_state=2, transition_time=$now");
    }
    $batch->update("state=".BATCH_STATE_RETIRED);
}

function batch_state_string($state) {
    switch ($state) {
    case BATCH_STATE_INIT: return "new";
    case BATCH_STATE_IN_PROGRESS: return "in progress";
    case BATCH_STATE_COMPLETE: return "completed";
    case BATCH_STATE_ABORTED: return "aborted";
    case BATCH_STATE_RETIRED: return "retired";
    }
    return "unknown state $state";
}
// get the total size of output files of a batch
//
function batch_output_file_size($batchid) {
    $batch_td_size=0;
    $wus = BoincWorkunit::enum("batch=$batchid");
    $fanout = parse_config(get_config(), "<uldl_dir_fanout>");
    $upload_dir = parse_config(get_config(), "<upload_dir>");
    foreach ($wus as $wu) {
        if (!$wu->canonical_resultid) continue;
        $result = BoincResult::lookup_id($wu->canonical_resultid);
        $names = get_outfile_names($result);
        foreach ($names as $name) {
            $path = dir_hier_path($name, $upload_dir, $fanout);
            if (is_file($path)) {
                $s=stat($path);
                $size=$s['size'];
                $batch_td_size+=$size;
            }
        }
    }
    return $batch_td_size;
}

function boinc_get_output_file_url($user, $result, $i) {
    $name = $result->name;
    $auth_str = md5($user->authenticator.$name);
    return "get_output.php?cmd=result_file&result_name=$name&file_num=$i&auth_str=$auth_str";
}

function boinc_get_output_files_url($user, $batch_id) {
    $auth_str = md5($user->authenticator.$batch_id);
    return "get_output.php?cmd=batch_files&batch_id=$batch_id&auth_str=$auth_str";
}

function boinc_get_wu_output_files_url($user, $wu_id) {
    
    //echo "url: user authenticator= $user->authenticator, wu_id=$wu_id<br/>";
    $auth_str =  md5($user->authenticator.$wu_id);
    return "get_output.php?cmd=workunit_files&wu_id=$wu_id&auth_str=$auth_str";
}
?>
