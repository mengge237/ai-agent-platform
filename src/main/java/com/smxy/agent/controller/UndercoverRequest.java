package com.smxy.agent.controller;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

public class UndercoverRequest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRoom {
        private List<String> players;

        private String category;

        private String wordSource;

        private Map<String, String> customWords;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Describe {
        private String roomId;

        private String playerId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Vote {
        private String roomId;

        private String playerId;

        private String discussion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Eliminate {
        private String roomId;

        private String playerId;
    }
}