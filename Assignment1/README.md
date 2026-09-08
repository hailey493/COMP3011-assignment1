# Assignment 1 - Speech to Text Web Application

## Overview
A website that allow user to make a recording and get the transcribe of that recording

## API Endpoints
/audio/transcribe
/admin/uptime
/global/stats
/admin/shutdown

## Testing
TITAN: 11/11 functional tests passed

JMeter:
250 concurrent blocking transcription requests
0% errors
Average response time: 2250 ms
Maximum response time: 3249 ms

The request was simulated using a 2 second test instead of the real OpenAI API

