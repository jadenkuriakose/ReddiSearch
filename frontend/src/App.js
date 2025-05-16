import React, { useState } from 'react';
import './App.css';

function App() {
  const [searchQuery, setSearchQuery] = useState('');
  const [results, setResults] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);

  const sampleResults = [
    {
      id: 1,
      title: "What's your favorite programming language?",
      subreddit: "r/programming",
      upvotes: 1542,
      comments: 328,
    },
    {
      id: 2,
      title:
        "TIL that honey never spoils. Archaeologists have found pots of honey in ancient Egyptian tombs that are over 3,000 years old and still perfectly good to eat.",
      subreddit: "r/todayilearned",
      upvotes: 24567,
      comments: 1203,
    },
    {
      id: 3,
      title: "What's a small thing that makes your day better?",
      subreddit: "r/AskReddit",
      upvotes: 9876,
      comments: 5432,
    },
    {
      id: 4,
      title: "Programmers, what's the most beautiful piece of code you've ever written?",
      subreddit: "r/coding",
      upvotes: 3421,
      comments: 892,
    },
    {
      id: 5,
      title: "What's the best way to learn JavaScript in 2023?",
      subreddit: "r/learnprogramming",
      upvotes: 782,
      comments: 341,
    },
  ];

  const handleSearch = () => {
    if (!searchQuery.trim()) {
      setResults([]);
      setHasSearched(false);
      return;
    }

    setIsLoading(true);
    setHasSearched(true);

    setTimeout(() => {

      const filteredResults = sampleResults.filter(
        (result) =>
          result.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
          result.subreddit.toLowerCase().includes(searchQuery.toLowerCase())
      );

      setResults(filteredResults);
      setIsLoading(false);
    }, 800); 
  };

  const handleKeyPress = (e) => {
    if (e.key === 'Enter') {
      handleSearch();
    }
  };

  return (
    <div className="app-container">
      <div className="container">
        <h1 className="title">ReddiSearch</h1>
        <p className="subtitle">Reddit answers at your fingertips</p>

        <div className="search-container">
          <input
            type="text"
            className="search-bar"
            placeholder="Search Reddit..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyPress={handleKeyPress}
          />
          <button className="search-button" onClick={handleSearch}>
            Search
          </button>
        </div>

        <div className="results-container">
          {isLoading ? (
            <div className="loading">
              <div className="loading-spinner"></div>
            </div>
          ) : hasSearched && results.length === 0 ? (
            <p className="no-results">
              No results found for "{searchQuery}". Try a different search term.
            </p>
          ) : (
            results.map((result) => (
              <div key={result.id} className="result-item">
                <h3>{result.title}</h3>
                <div className="result-meta">
                  <span className="subreddit">{result.subreddit}</span>
                  <span className="upvotes">👍 {result.upvotes}</span>
                  <span className="comments">💬 {result.comments}</span>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

export default App;