package org.apache.streams.mvc.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.streams.components.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/*
* This class contains all entry and exit points to the application
* */
@Controller
@RequestMapping("/*")
public class StreamsWebController {
    private Log log = LogFactory.getLog(StreamsWebController.class);

    private StreamsPublisherRegistrationService publisherRegistrationService;
    private StreamsSubscriberRegistrationService subscriberRegistrationService;
    private StreamsActivityPublishingService activityPublishingService;
    private StreamsActivityReceivingService activityReceivingService;
    private StreamsFiltersService filtersService;

    @Autowired
    public StreamsWebController(
            StreamsPublisherRegistrationService publisherRegistrationService,
            StreamsSubscriberRegistrationService subscriberRegistrationService,
            StreamsActivityPublishingService activityPublishingService,
            StreamsActivityReceivingService activityReceivingService,
            StreamsFiltersService filtersService) {
        this.publisherRegistrationService = publisherRegistrationService;
        this.subscriberRegistrationService = subscriberRegistrationService;
        this.activityPublishingService = activityPublishingService;
        this.activityReceivingService = activityReceivingService;
        this.filtersService = filtersService;
    }

    /**
     * this method is the entry point for registering publishers
     *
     * @param payload json of the publisher to be registered
     * @return a url that a publisher can post to
     */
    @RequestMapping(headers = {"content-type=application/json"}, value = "/publisherRegister", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> registerPublisher(@RequestBody String payload, @RequestHeader("host") String host) {
        try {
            return new ResponseEntity<String>("http://" + host + "/streams-web/app/postActivity/" + publisherRegistrationService.register(payload), HttpStatus.OK);
        } catch (Exception e) {
            log.error(e);
            return new ResponseEntity<String>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * this method is the entry point for registering subscribers
     *
     * @param payload json of the subscriber to be registered
     * @return a url that can GET activity of a subscriber
     */
    @RequestMapping(headers = {"content-type=application/json"}, value = "/subscriberRegister", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> registerSubscriber(@RequestBody String payload, @RequestHeader("host") String host) {
        try {
            return new ResponseEntity<String>("http://" + host + "/streams-web/app/getActivity/" + subscriberRegistrationService.register(payload), HttpStatus.OK);
        } catch (Exception e) {
            log.error(e);
            return new ResponseEntity<String>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * this method is the entry point for posting activity
     *
     * @param publisherID the id of this publisher
     * @param payload     json of the activity to be published
     * @return a success message if the activity post was successful
     */
    @RequestMapping(headers = {"content-type=application/json"}, value = "/postActivity/{publisherID}", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> postActivity(@PathVariable("publisherID") String publisherID, @RequestBody String payload) {
        try {
            return new ResponseEntity<String>(activityPublishingService.publish(publisherID, payload), HttpStatus.OK);
        } catch (Exception e) {
            log.error(e);
            return new ResponseEntity<String>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * this method is the entry po int for receiving activity
     *
     * @param subscriberID the id of the subscriber
     * @return an array of activity for this subscriber
     */
    @RequestMapping(value = "/getActivity/{subscriberID}", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getActivity(@PathVariable("subscriberID") String subscriberID) {
        try {
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            return new ResponseEntity<String>(activityReceivingService.getActivity(subscriberID), responseHeaders, HttpStatus.OK);
        } catch (Exception e) {
            log.error(e);
            return new ResponseEntity<String>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * this method is the entry point for updating filters
     *
     * @param subscriberID the id of the subscriber
     * @return a message indicating whether the update was successful
     */
    @RequestMapping(value = "/updateFilters/{subscriberID}", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> updateTags(@PathVariable("subscriberID") String subscriberID, @RequestBody String payload) {
        try {
            return new ResponseEntity<String>(filtersService.updateFilters(subscriberID, payload), HttpStatus.OK);
        } catch (Exception e) {
            log.error(e);
            return new ResponseEntity<String>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * this method is the entry point for getting filters
     *
     * @param subscriberID the id of the subscriber
     * @return an array of filters for this subscriber
     */
    @RequestMapping(value = "/getFilters/{subscriberID}", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> getTags(@PathVariable("subscriberID") String subscriberID) {
        try {
        	HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            return new ResponseEntity<String>(filtersService.getFilters(subscriberID), HttpStatus.OK);
        } catch (Exception e) {
            log.error(e);
            return new ResponseEntity<String>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
